package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 坐席无操作自动离线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentInactiveScheduler {

    private final AgentSessionActivityService sessionActivityService;
    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;

    @Value("${agent.inactive.minutes:3}")
    private int inactiveMinutes;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void scanInactiveAgent() {
        sessionActivityService.scanInactive(inactiveMinutes).forEach(state -> {
            agentDispatchService.markOffline(state.agentRowId());
            if (state.loginRecordRowId() != null && !state.loginRecordRowId().isBlank()) {
                crmOpenApiService.updateAgentLoginStatus(state.loginRecordRowId(), "离线");
            }
            log.info("Agent auto-offline by inactivity. agent={}, thresholdMinutes={}", state.agentRowId(), inactiveMinutes);
        });
    }
}
