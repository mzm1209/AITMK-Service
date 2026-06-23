package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.service.ConversationService;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {
    private static final Duration NEW_CONVERSATION_AFTER = Duration.ofDays(30);
    private static final EnumSet<ConversationStatus> ACTIVE = EnumSet.of(
            ConversationStatus.ACTIVE, ConversationStatus.AI_ACTIVE, ConversationStatus.HUMAN_ACTIVE);
    private final ConversationRepository repository;
    private final RealtimeEventService events;
    private final RealtimePayloadFactory payloads;

    @Override @Transactional
    public ConversationEntity getOrCreateActive(ResourceEntity resource, String businessAccountId, String channel) {
        Instant now = Instant.now();
        ConversationEntity current = repository.findActiveForUpdate(resource.getId(), ACTIVE).stream().findFirst().orElse(null);
        if (current != null && current.getLastMessageAt() != null
                && Duration.between(current.getLastMessageAt(), now).compareTo(NEW_CONVERSATION_AFTER) > 0) {
            current.setStatus(ConversationStatus.CLOSED);
            current.setClosedAt(now);
            current.setClosedBy("SYSTEM");
            current.setCloseReason("30_DAY_INACTIVITY");
            // The generated unique column is released only after the close UPDATE reaches the DB.
            repository.saveAndFlush(current);
            if (current.getAssignedAgentId() != null) {
                events.append("CONVERSATION_UPDATED", "CONVERSATION", current.getId(), resource.getId(),
                        current.getId(), current.getAssignedAgentId(), current.getVersion(),
                        payloads.conversation(current, current.getAssignedAgentId()));
            }
            current = null;
        }
        if (current != null) return current;
        ConversationEntity created = new ConversationEntity();
        created.setResourceId(resource.getId());
        created.setCustomerPhone(resource.getCustomerPhone());
        created.setBusinessAccountId(businessAccountId);
        try { created.setChannel(SourceChannel.valueOf(channel == null ? "META" : channel.toUpperCase())); }
        catch (IllegalArgumentException ignored) { created.setChannel(SourceChannel.META); }
        created = repository.saveAndFlush(created);
        if (resource.getAssignedAgentId() != null) {
            events.append("CONVERSATION_CREATED", "CONVERSATION", created.getId(), resource.getId(),
                    created.getId(), resource.getAssignedAgentId(), created.getVersion(),
                    payloads.conversation(created, resource.getAssignedAgentId()));
        }
        return created;
    }
}
