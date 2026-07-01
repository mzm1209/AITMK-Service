package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.ConversationAgentStateEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.PersistenceEnums.SenderType;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.repository.ConversationAgentStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UnreadService {
    private final ConversationAgentStateRepository states;
    private final ChatMessageRepository messages;
    private final RealtimeEventService events;

    @Transactional
    public void increment(ConversationEntity c) {
        if (c.getAssignedAgentId() == null) return;
        var s = states.findForUpdate(c.getId(), c.getAssignedAgentId())
                .orElseGet(() -> state(c.getId(), c.getAssignedAgentId()));
        s.setUnreadCount(s.getUnreadCount() + 1);
        states.save(s);
        appendChanged(c, c.getAssignedAgentId(), s);
    }

    @Transactional
    public void initializeForAssignment(ConversationEntity c, String agentId) {
        if (c == null || agentId == null || agentId.isBlank()) return;
        var s = states.findForUpdate(c.getId(), agentId).orElseGet(() -> state(c.getId(), agentId));
        long unread = s.getLastReadMessageId() == null
                ? messages.countByConversationIdAndSenderType(c.getId(), SenderType.CUSTOMER)
                : messages.countByConversationIdAndSenderTypeAndIdGreaterThan(
                        c.getId(), SenderType.CUSTOMER, s.getLastReadMessageId());
        if (s.getUnreadCount() == unread) return;
        s.setUnreadCount(unread);
        states.save(s);
        appendChanged(c, agentId, s);
    }

    @Transactional
    public V2Api.ReadResult read(ConversationEntity c, String agent, Long messageId) {
        var m = messages.findById(messageId).orElseThrow(() ->
                new V2Exception(HttpStatus.NOT_FOUND, "MESSAGE_NOT_FOUND", "消息不存在"));
        if (!m.getConversationId().equals(c.getId())) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "MESSAGE_CONVERSATION_MISMATCH", "消息不属于该会话");
        }
        var s = states.findForUpdate(c.getId(), agent).orElseGet(() -> state(c.getId(), agent));
        if (s.getLastReadMessageId() == null || messageId > s.getLastReadMessageId()) {
            s.setLastReadMessageId(messageId);
            s.setLastReadAt(Instant.now());
            s.setUnreadCount(0);
            states.save(s);
            appendChanged(c, agent, s);
        }
        return new V2Api.ReadResult(c.getId().toString(), agent, V2Mapper.s(s.getLastReadMessageId()),
                s.getLastReadAt(), s.getUnreadCount());
    }

    private void appendChanged(ConversationEntity c, String agentId, ConversationAgentStateEntity s) {
        events.append("UNREAD_COUNT_CHANGED", "CONVERSATION", c.getId(), c.getResourceId(), c.getId(), agentId,
                c.getVersion(), new V2Api.UnreadCountPayload(s.getUnreadCount(), V2Mapper.s(s.getLastReadMessageId())));
    }

    private ConversationAgentStateEntity state(Long c, String a) {
        var s = new ConversationAgentStateEntity();
        s.setConversationId(c);
        s.setAgentId(a);
        return s;
    }
}
