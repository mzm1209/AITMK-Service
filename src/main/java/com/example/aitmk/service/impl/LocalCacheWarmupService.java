package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCacheWarmupService {
    private final AgentDispatchService dispatchService;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        int activeAssignments = dispatchService.assignmentsSnapshot().size();
        log.info("Local database cache warmup completed. activeAssignments={}", activeAssignments);
    }
}
