package com.example.aitmk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronous handler for online-agent customer assignment.
 * Runs after the agent status is updated to ONLINE, so the HTTP
 * response returns immediately to the frontend.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOnlineAssignmentHandler {

    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;
    private final ChatHistoryService chatHistoryService;
    private final AgentPushService agentPushService;

    /**
     * Assign up to {@code maxAssignments} pending customers to the given agent,
     * then sync to CRM and push chat history.  Runs asynchronously — failures
     * are logged but never propagated to the caller.
     */
    @Async
    public void assignPendingCustomers(String agentRowId, int maxAssignments) {
        int assigned = 0;
        while (assigned < maxAssignments) {
            try {
                var pending = agentDispatchService.assignOnePendingCustomerToAgent(agentRowId);
                if (pending.isEmpty()) {
                    break;
                }
                String customerPhone = pending.get();
                crmOpenApiService.addAssignmentRecord(customerPhone, agentRowId, "服务中");
                crmOpenApiService.assignAiReception(customerPhone);
                agentPushService.pushHistory(
                        agentRowId, customerPhone,
                        chatHistoryService.listMessages(customerPhone));
                assigned++;
            } catch (Exception ex) {
                log.warn("Pending customer assignment failed, continue. agent={}", agentRowId, ex);
            }
        }
        if (assigned > 0) {
            log.info("Online assignment complete: agent={} assigned={}", agentRowId, assigned);
        }
    }
}
