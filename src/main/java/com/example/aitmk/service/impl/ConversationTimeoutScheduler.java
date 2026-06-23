package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 历史超时扫描逻辑已下线（不再基于本地内存进行会话回收）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ConversationTimeoutScheduler {

    private final AgentDispatchService agentDispatchService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void scan() {
        agentDispatchService.scanTimeouts(5, 10);
    }
}
