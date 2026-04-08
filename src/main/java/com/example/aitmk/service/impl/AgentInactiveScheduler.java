package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 坐席无操作自动离线（当前测试阈值：1分钟）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentInactiveScheduler {

    private final AgentSessionActivityService sessionActivityService;
    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void scanInactiveAgent() {
        sessionActivityService.scanInactive(1).forEach(state -> {
            agentDispatchService.markOffline(state.agentRowId());
            if (state.loginRecordRowId() != null && !state.loginRecordRowId().isBlank()) {
                crmOpenApiService.updateAgentLoginStatus(state.loginRecordRowId(), "离线");
            }
            log.info("Agent auto-offline by inactivity. agent={}", state.agentRowId());
        });
    }
}

