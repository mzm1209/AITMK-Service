package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.RealtimeEventEntity;
import com.example.aitmk.repository.RealtimeEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RealtimeEventService {
    private final RealtimeEventRepository repo;
    private final ObjectMapper json;

    @Transactional
    public RealtimeEventEntity append(String type, String aggregate, Long aggregateId, Long resourceId,
            Long conversationId, String target, Long version, Object payload) {
        if (target == null) return null;
        RealtimeEventEntity event = new RealtimeEventEntity();
        event.setEventType(type);
        event.setAggregateType(aggregate);
        event.setAggregateId(aggregateId);
        event.setResourceId(resourceId);
        event.setConversationId(conversationId);
        event.setTargetAgentId(target);
        event.setAggregateVersion(version);
        try {
            event.setPayloadJson(json.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Realtime event payload serialization failed", ex);
        }
        return repo.save(event);
    }

    @Transactional(readOnly = true)
    public V2Api.CursorPage<V2Api.EventView> recover(String agent, String after, int requested) {
        int size = Math.min(Math.max(requested, 1), 200);
        long id = 0;
        if (after != null && !after.isBlank()) {
            id = repo.findByEventIdAndTargetAgentId(after, agent)
                    .orElseThrow(() -> new V2Exception(HttpStatus.GONE, "EVENT_CURSOR_EXPIRED", "事件游标已失效"))
                    .getId();
        }
        List<RealtimeEventEntity> rows = id == 0
                ? repo.findByTargetAgentIdOrderByIdAsc(agent, PageRequest.of(0, size + 1))
                : repo.findByTargetAgentIdAndIdGreaterThanOrderByIdAsc(agent, id, PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<V2Api.EventView> views = rows.stream().map(this::view).toList();
        return new V2Api.CursorPage<>(views,
                views.isEmpty() ? null : views.get(views.size() - 1).eventId(), more);
    }

    public V2Api.EventView view(RealtimeEventEntity event) {
        try {
            Object payload = json.readValue(event.getPayloadJson(), Object.class);
            return new V2Api.EventView(event.getEventId(), event.getEventType(), event.getOccurredAt(),
                    event.getAggregateVersion(), V2Mapper.s(event.getResourceId()),
                    V2Mapper.s(event.getConversationId()), payload);
        } catch (JsonProcessingException ex) {
            throw new V2Exception(HttpStatus.INTERNAL_SERVER_ERROR, "EVENT_PAYLOAD_CORRUPTED", "实时事件数据损坏");
        }
    }
}
