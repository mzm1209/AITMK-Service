package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.ConversationSummary;
import com.example.aitmk.model.api.v2.V2Api.MessageView;
import com.example.aitmk.model.entity.ChatMessageEntity;
import com.example.aitmk.model.entity.ConversationAgentStateEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.ConversationStatus;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.repository.ConversationAgentStateRepository;
import com.example.aitmk.repository.ResourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;

/** Builds immutable realtime payload snapshots while the business transaction is still active. */
@Service
@RequiredArgsConstructor
public class RealtimePayloadFactory {
    private final ResourceRepository resources;
    private final ConversationAgentStateRepository states;
    private final ChatMessageRepository messages;

    public MessageView message(ChatMessageEntity message) {
        return V2Mapper.message(message);
    }

    public ConversationSummary conversation(ConversationEntity conversation, String targetAgentId) {
        ResourceEntity resource = resources.findById(conversation.getResourceId()).orElseThrow();
        ConversationAgentStateEntity state = targetAgentId == null ? null
                : states.findByConversationIdAndAgentId(conversation.getId(), targetAgentId).orElse(null);
        ChatMessageEntity lastMessage = messages.findByConversationIdOrderByCreatedAtDescIdDesc(
                conversation.getId(), PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        Instant deadline = resource.getLastCustomerMessageAt() == null ? null
                : resource.getLastCustomerMessageAt().plusSeconds(86400);
        boolean replyable = targetAgentId != null
                && targetAgentId.equals(conversation.getAssignedAgentId())
                && conversation.getStatus() != ConversationStatus.CLOSED
                && deadline != null && !deadline.isBefore(Instant.now());
        return V2Mapper.conversation(conversation, resource, state, lastMessage, replyable);
    }
}
