package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.RealtimeEventEntity;
import com.example.aitmk.repository.RealtimeEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RealtimeEventPublisherTest {
    @Test
    void socketUsesTheSameEventViewAsRecoveryDecoder() {
        RealtimeEventRepository repo = mock(RealtimeEventRepository.class);
        SimpMessagingTemplate socket = mock(SimpMessagingTemplate.class);
        RealtimeEventService events = mock(RealtimeEventService.class);
        RealtimeEventEntity event = event();
        V2Api.EventView expected = new V2Api.EventView(event.getEventId(), event.getEventType(),
                event.getOccurredAt(), 3L, "10", "20", java.util.Map.of("messageId", "30"));
        when(repo.lockUnpublished(eq(20), any())).thenReturn(List.of(event));
        when(events.view(event)).thenReturn(expected);

        new RealtimeEventPublisher(repo, socket, events).publish();

        ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
        verify(socket).convertAndSendToUser(eq("agent"), eq("/queue/events"), envelope.capture());
        assertThat(envelope.getValue()).isSameAs(expected);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getPublishAttempts()).isZero();
    }

    @Test
    void corruptedPayloadIncrementsAttemptsAndIsNotMarkedPublished() {
        RealtimeEventRepository repo = mock(RealtimeEventRepository.class);
        SimpMessagingTemplate socket = mock(SimpMessagingTemplate.class);
        RealtimeEventService events = mock(RealtimeEventService.class);
        RealtimeEventEntity event = event();
        when(repo.lockUnpublished(eq(20), any())).thenReturn(List.of(event));
        when(events.view(event)).thenThrow(new V2Exception(HttpStatus.INTERNAL_SERVER_ERROR,
                "EVENT_PAYLOAD_CORRUPTED", "实时事件数据损坏"));

        new RealtimeEventPublisher(repo, socket, events).publish();

        verifyNoInteractions(socket);
        assertThat(event.getPublishAttempts()).isEqualTo(1);
        assertThat(event.getPublishedAt()).isNull();
        verify(repo).save(event);
    }

    private RealtimeEventEntity event() {
        RealtimeEventEntity event = new RealtimeEventEntity();
        event.setEventId("event-1");
        event.setEventType("MESSAGE_CREATED");
        event.setTargetAgentId("agent");
        event.setOccurredAt(Instant.EPOCH);
        return event;
    }
}
