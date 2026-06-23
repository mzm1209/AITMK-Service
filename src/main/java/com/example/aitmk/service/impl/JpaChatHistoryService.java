package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.PageResult;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Primary
@RequiredArgsConstructor
public class JpaChatHistoryService implements ChatHistoryService {
    private final ResourceRepository resources;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ConversationService conversationService;

    @Override @Transactional public void recordCustomerMessage(String id, String text) { append(id, text, SenderType.CUSTOMER, null); }
    @Override @Transactional public void recordAiReply(String id, String text) { append(id, text, SenderType.AI, null); }
    @Override @Transactional public void recordManualReply(String id, String text) { append(id, text, SenderType.AGENT, null); }

    @Override @Transactional
    public void setCustomerNickname(String id, String nickname) {
        if (!StringUtils.hasText(id) || !StringUtils.hasText(nickname)) return;
        ResourceEntity resource = getOrCreateLocked(id);
        String normalized = nickname.trim();
        if (normalized.equals(resource.getCustomerName())) return;
        resource.setCustomerName(normalized);
        resources.save(resource);
    }

    @Override @Transactional(readOnly = true)
    public List<ChatCustomer> listCustomers() {
        return resources.findAllByLastMessageAtIsNotNull(Sort.by(Sort.Direction.DESC, "lastMessageAt")).stream()
                .map(r -> {
                    ChatMessageEntity latest = messages.findFirstByCustomerPhoneOrderByCreatedAtDescIdDesc(r.getCustomerPhone()).orElse(null);
                    return ChatCustomer.builder().customerId(r.getCustomerPhone()).customerNickname(orEmpty(r.getCustomerName()))
                            .lastMessage(latest == null ? "" : latest.getContent()).lastMessageAt(r.getLastMessageAt()).build();
                }).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<ChatMessageRecord> listMessages(String id) {
        return messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc(id).stream().map(this::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public PageResult<ChatMessageRecord> listMessagesPaged(String id, int page, int size, boolean desc) {
        int p = Math.max(page, 1), s = Math.min(Math.max(size, 1), 50);
        Sort sort = Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, "createdAt").and(Sort.by(desc ? Sort.Direction.DESC : Sort.Direction.ASC, "id"));
        Page<ChatMessageEntity> result = messages.findByCustomerPhone(id, PageRequest.of(p - 1, s, sort));
        return PageResult.<ChatMessageRecord>builder().items(result.map(this::toDomain).getContent())
                .page(p).size(s).total(result.getTotalElements()).hasNext(result.hasNext()).build();
    }

    @Override @Transactional(readOnly = true)
    public Optional<Instant> lastCustomerMessageTime(String id) {
        return resources.findByCustomerPhone(id).map(ResourceEntity::getLastCustomerMessageAt)
                .or(() -> messages.findFirstByCustomerPhoneAndSenderTypeOrderByCreatedAtDescIdDesc(id, SenderType.CUSTOMER).map(ChatMessageEntity::getCreatedAt));
    }

    @Override @Transactional(readOnly = true)
    public Map<String, List<ChatMessageRecord>> snapshot() {
        return messages.findAll(Sort.by("customerPhone", "createdAt", "id")).stream()
                .map(this::toDomain).collect(Collectors.groupingBy(ChatMessageRecord::getCustomerId, LinkedHashMap::new, Collectors.toList()));
    }

    @Override @Transactional
    public void replaceAll(Map<String, List<ChatMessageRecord>> records) {
        if (records == null) return;
        records.forEach((customer, history) -> {
            if (history == null) return;
            history.stream().sorted(Comparator.comparing(ChatMessageRecord::getTimestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(record -> append(customer, record.getMessage(), parseSender(record.getSender()), record.getTimestamp()));
        });
    }

    private void append(String id, String text, SenderType sender, Instant suppliedAt) {
        if (!StringUtils.hasText(id)) throw new IllegalArgumentException("customerId must not be blank");
        ResourceEntity resource = getOrCreateLocked(id);
        ConversationEntity conversation = conversationService.getOrCreateActive(resource, null, "META");
        Instant at = suppliedAt == null ? Instant.now() : suppliedAt;
        ChatMessageEntity message = new ChatMessageEntity();
        message.setResourceId(resource.getId()); message.setConversationId(conversation.getId()); message.setCustomerPhone(id);
        message.setSenderType(sender); message.setContent(text); message.setMessageType(MessageType.TEXT); message.setCreatedAt(at);
        message.setSentStatus(sender == SenderType.CUSTOMER ? SentStatus.DELIVERED : SentStatus.SENT);
        if (sender != SenderType.CUSTOMER) message.setSentAt(at); else message.setDeliveredAt(at);
        messages.save(message);
        resource.setLastMessageAt(at);
        if (sender == SenderType.CUSTOMER) resource.setLastCustomerMessageAt(at);
        if (sender == SenderType.AGENT || sender == SenderType.MANAGER) resource.setLastAgentMessageAt(at);
        resources.save(resource);
        conversation.setLastMessageAt(at);
        if (sender == SenderType.CUSTOMER && conversation.getFirstCustomerMessageAt() == null) conversation.setFirstCustomerMessageAt(at);
        if (sender == SenderType.AI && conversation.getFirstAiReplyAt() == null) conversation.setFirstAiReplyAt(at);
        if ((sender == SenderType.AGENT || sender == SenderType.MANAGER) && conversation.getFirstAgentReplyAt() == null) conversation.setFirstAgentReplyAt(at);
        conversations.save(conversation);
    }

    private ResourceEntity getOrCreateLocked(String id) {
        return resources.findByCustomerPhoneForUpdate(id).orElseGet(() -> { ResourceEntity r = new ResourceEntity(); r.setCustomerPhone(id); r.setSourceExternalId(id); return resources.saveAndFlush(r); });
    }
    private ChatMessageRecord toDomain(ChatMessageEntity m) {
        return ChatMessageRecord.builder().customerId(m.getCustomerPhone()).sender(m.getSenderType().name().toLowerCase())
                .message(m.getContent()).timestamp(m.getCreatedAt()).operatorId(m.getSenderId()).operatorRole(m.getOperatorRole()).build();
    }
    private SenderType parseSender(String value) { try { return SenderType.valueOf(value.toUpperCase()); } catch (Exception e) { return SenderType.SYSTEM; } }
    private String orEmpty(String value) { return value == null ? "" : value; }
}
