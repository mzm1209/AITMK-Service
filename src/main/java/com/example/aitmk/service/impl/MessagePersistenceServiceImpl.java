package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ChatMessageEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.ConversationService;
import com.example.aitmk.service.MessagePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.Instant;
import com.example.aitmk.service.v2.UnreadService;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;

@Service
@RequiredArgsConstructor
public class MessagePersistenceServiceImpl implements MessagePersistenceService {
    private final ResourceRepository resourceRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final ConversationService conversationService;
    private final UnreadService unreadService;
    private final RealtimeEventService realtimeEventService;
    private final RealtimePayloadFactory realtimePayloadFactory;

    @Override @Transactional(readOnly = true)
    public boolean existsExternalMessage(String id) {
        return StringUtils.hasText(id) && messageRepository.existsByExternalMessageId(id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IncomingResult recordIncoming(String phone, String accountId, String externalId, String type, String content,
                               String mediaId, String mediaUrl, String mimeType, String rawPayload, Instant receivedAt) {
        if (StringUtils.hasText(externalId) && messageRepository.existsByExternalMessageId(externalId)) return IncomingResult.DUPLICATE;
        ResourceEntity resource = resourceRepository.findByCustomerPhoneForUpdate(phone).orElseGet(() -> {
            ResourceEntity created = new ResourceEntity();
            created.setCustomerPhone(phone);
            created.setSourceExternalId(phone);
            return resourceRepository.saveAndFlush(created);
        });
        ConversationEntity conversation = conversationService.getOrCreateActive(resource, accountId, "META");
        Instant at = receivedAt == null ? Instant.now() : receivedAt;
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(conversation.getId());
        message.setResourceId(resource.getId());
        message.setCustomerPhone(phone);
        message.setBusinessAccountId(accountId);
        message.setExternalMessageId(StringUtils.hasText(externalId) ? externalId : null);
        message.setSenderType(SenderType.CUSTOMER);
        message.setMessageType(parseMessageType(type));
        message.setContent(content);
        message.setMediaId(mediaId);
        message.setMediaUrl(mediaUrl);
        message.setMimeType(mimeType);
        message.setRawPayload(rawPayload);
        message.setSentStatus(SentStatus.DELIVERED);
        message.setDeliveredAt(at);
        message.setCreatedAt(at);
        messageRepository.saveAndFlush(message);

        resource.setLastMessageAt(max(resource.getLastMessageAt(), at));
        resource.setLastCustomerMessageAt(max(resource.getLastCustomerMessageAt(), at));
        resourceRepository.saveAndFlush(resource);
        if (conversation.getFirstCustomerMessageAt() == null) conversation.setFirstCustomerMessageAt(at);
        conversation.setLastMessageAt(max(conversation.getLastMessageAt(), at));
        conversationRepository.saveAndFlush(conversation);
        unreadService.increment(conversation);
        realtimeEventService.append("MESSAGE_CREATED", "MESSAGE", message.getId(), resource.getId(), conversation.getId(),
                conversation.getAssignedAgentId(), conversation.getVersion(), realtimePayloadFactory.message(message));
        return IncomingResult.CREATED;
    }

    @Override @Transactional
    public void updateDeliveryStatus(String id, SentStatus next, Instant occurredAt, String reason) {
        if (!StringUtils.hasText(id) || next == null) return;
        messageRepository.findByExternalMessageId(id).ifPresent(message -> {
            if (!canAdvance(message.getSentStatus(), next)) return;
            Instant at = occurredAt == null ? Instant.now() : occurredAt;
            message.setSentStatus(next);
            switch (next) {
                case SENT -> message.setSentAt(at);
                case DELIVERED -> message.setDeliveredAt(at);
                case READ -> message.setReadAt(at);
                case FAILED -> { message.setFailedAt(at); message.setFailureReason(truncate(reason)); }
                default -> { }
            }
            messageRepository.saveAndFlush(message);
            statusEvent(message);
        });
    }

    @Override @Transactional
    public long createOutgoing(String phone, String accountId, SenderType sender, String senderId, String role,
                               MessageType type, String content, String mediaId, String mediaUrl, String mimeType) {
        ResourceEntity resource = resourceRepository.findByCustomerPhoneForUpdate(phone).orElseGet(() -> {
            ResourceEntity created = new ResourceEntity(); created.setCustomerPhone(phone); created.setSourceExternalId(phone);
            return resourceRepository.saveAndFlush(created);
        });
        ConversationEntity conversation = conversationService.getOrCreateActive(resource, accountId, "META");
        Instant now = Instant.now();
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(conversation.getId()); message.setResourceId(resource.getId()); message.setCustomerPhone(phone);
        message.setBusinessAccountId(accountId); message.setSenderType(sender); message.setSenderId(senderId); message.setOperatorRole(role);
        message.setMessageType(type == null ? MessageType.TEXT : type); message.setContent(content); message.setMediaId(mediaId);
        message.setMediaUrl(mediaUrl); message.setMimeType(mimeType); message.setSentStatus(SentStatus.PENDING); message.setCreatedAt(now);
        messageRepository.saveAndFlush(message);
        resource.setLastMessageAt(now); if (sender == SenderType.AGENT || sender == SenderType.MANAGER) resource.setLastAgentMessageAt(now);
        resourceRepository.saveAndFlush(resource);
        conversation.setLastMessageAt(now);
        if (sender == SenderType.AI && conversation.getFirstAiReplyAt() == null) conversation.setFirstAiReplyAt(now);
        if ((sender == SenderType.AGENT || sender == SenderType.MANAGER) && conversation.getFirstAgentReplyAt() == null) conversation.setFirstAgentReplyAt(now);
        conversationRepository.saveAndFlush(conversation);
        realtimeEventService.append("MESSAGE_CREATED", "MESSAGE", message.getId(), resource.getId(), conversation.getId(),
                conversation.getAssignedAgentId(), conversation.getVersion(), realtimePayloadFactory.message(message));
        return message.getId();
    }

    @Override @Transactional
    public void markOutgoingSent(long localId, String externalId, Instant at) {
        messageRepository.findById(localId).ifPresent(message -> {
            message.setExternalMessageId(StringUtils.hasText(externalId) ? externalId : null);
            message.setSentStatus(SentStatus.SENT); message.setSentAt(at == null ? Instant.now() : at); messageRepository.saveAndFlush(message);
            statusEvent(message);
        });
    }

    @Override @Transactional
    public void markOutgoingFailed(long localId, String reason, Instant at) {
        messageRepository.findById(localId).ifPresent(message -> {
            if (message.getSentStatus() == SentStatus.READ) return;
            message.setSentStatus(SentStatus.FAILED); message.setFailedAt(at == null ? Instant.now() : at);
            message.setFailureReason(truncate(reason)); messageRepository.saveAndFlush(message);
            statusEvent(message);
        });
    }

    private void statusEvent(ChatMessageEntity message) {
        conversationRepository.findById(message.getConversationId()).ifPresent(c ->
                realtimeEventService.append("MESSAGE_STATUS_CHANGED", "MESSAGE", message.getId(), message.getResourceId(),
                        message.getConversationId(), c.getAssignedAgentId(), c.getVersion(),
                        realtimePayloadFactory.message(message)));
    }

    private boolean canAdvance(SentStatus current, SentStatus next) {
        if (next == SentStatus.FAILED) return current != SentStatus.READ;
        if (current == SentStatus.FAILED) return false;
        return rank(next) >= rank(current);
    }
    private int rank(SentStatus s) { return switch (s) { case PENDING -> 0; case SENT -> 1; case DELIVERED -> 2; case READ -> 3; case FAILED -> 4; }; }
    private String truncate(String value) { return value == null ? null : value.substring(0, Math.min(1000, value.length())); }
    private Instant max(Instant current, Instant candidate) { return current == null || candidate.isAfter(current) ? candidate : current; }
    private MessageType parseMessageType(String value) {
        if (!StringUtils.hasText(value)) return MessageType.TEXT;
        try { return MessageType.valueOf(value.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return MessageType.SYSTEM; }
    }
}
