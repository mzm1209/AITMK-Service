package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AgentPushService;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 会话超时扫描：
 * - 5分钟未回复提醒
 * - 10分钟未回复回收至AI池
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationTimeoutScheduler {

    private final AgentDispatchService agentDispatchService;
    private final AgentPushService agentPushService;
    private final CrmOpenApiService crmOpenApiService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    public void scan() {
        AgentDispatchService.TimeoutScanResult result = agentDispatchService.scanTimeouts(5, 10);

        result.overdueWarnCustomers().forEach(customer -> agentDispatchService.getAssignedAgent(customer).ifPresent(agent -> {
            ChatMessageRecord tip = ChatMessageRecord.builder()
                    .customerId(customer)
                    .sender("system")
                    .message("提醒：该客户已超过5分钟未回复，请尽快处理")
                    .timestamp(Instant.now())
                    .build();
            agentPushService.pushNewMessage(agent, customer, tip);
        }));

        result.reclaimedCustomers().forEach(customer -> {
            try {
                crmOpenApiService.closeServingAssignment(customer);
                crmOpenApiService.openAiReception(customer, "未及时回复");
            } catch (Exception e) {
                log.warn("Open AI reception on reclaim failed. customer={}", customer, e);
            }
        });

        if (!result.overdueWarnCustomers().isEmpty() || !result.reclaimedCustomers().isEmpty()) {
            log.info("Timeout scan done. warnCount={}, reclaimCount={}",
                    result.overdueWarnCustomers().size(), result.reclaimedCustomers().size());
        }
    }
}
