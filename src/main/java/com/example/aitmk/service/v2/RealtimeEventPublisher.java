package com.example.aitmk.service.v2;

import com.example.aitmk.repository.RealtimeEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RealtimeEventPublisher {
    private final RealtimeEventRepository repo;
    private final SimpMessagingTemplate socket;
    private final RealtimeEventService events;

    @Scheduled(fixedDelayString = "${realtime.outbox.delay-ms:1000}")
    @Transactional
    public void publish() {
        for (var event : repo.lockUnpublished(20, PageRequest.of(0, 100))) {
            try {
                var envelope = events.view(event);
                socket.convertAndSendToUser(event.getTargetAgentId(), "/queue/events", envelope);
                event.setPublishedAt(Instant.now());
            } catch (Exception ex) {
                event.setPublishAttempts(event.getPublishAttempts() + 1);
                log.error("Realtime event publish failed. eventId={}, eventType={}, targetAgentId={}, attempts={}, errorType={}",
                        event.getEventId(), event.getEventType(), event.getTargetAgentId(),
                        event.getPublishAttempts(), ex.getClass().getSimpleName());
            }
            repo.save(event);
        }
    }
}
