package com.example.aitmk.service;

import com.example.aitmk.service.impl.ClueIntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final ClueIntegrationService clueIntegrationService;
    @Value("${agent.pending-assignment.sleep-every:100}")
    private int sleepEveryAssignments;
    @Value("${agent.pending-assignment.sleep-ms:50}")
    private long sleepMs;

    /**
     * Drain all currently assignable pending customers, then sync to CRM and
     * push chat history. Runs asynchronously; failures are logged but never
     * propagated to the caller.
     */
    @Async
    public void assignPendingCustomers(String agentRowId) {
        int assigned = 0;
        while (true) {
            try {
                var pending = agentDispatchService.assignOnePendingCustomerToAgent(agentRowId);
                if (pending.isEmpty()) {
                    break;
                }
                String customerPhone = pending.get();
                String assignedAgent = agentDispatchService.getAssignedAgent(customerPhone).orElse(agentRowId);
                syncLead(customerPhone, assignedAgent);
                crmOpenApiService.addAssignmentRecord(customerPhone, assignedAgent, "服务中");
                crmOpenApiService.assignAiReception(customerPhone);
                agentPushService.pushHistory(
                        assignedAgent, customerPhone,
                        chatHistoryService.listMessages(customerPhone));
                assigned++;
                maybeThrottle(assigned);
            } catch (Exception ex) {
                log.warn("Pending customer assignment failed, continue. agent={}", agentRowId, ex);
            }
        }
        if (assigned > 0) {
            log.info("Online assignment complete: agent={} assigned={}", agentRowId, assigned);
        }
    }

    private void maybeThrottle(int assigned) {
        if (sleepMs <= 0 || sleepEveryAssignments <= 0 || assigned % sleepEveryAssignments != 0) return;
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Online assignment throttle interrupted");
        }
    }

    private void syncLead(String customerPhone, String assignedAgent) {
        try {
            String activityRowId = clueIntegrationService.resolveActivityRowIdForCustomer(customerPhone).orElse(null);
            var lead = clueIntegrationService.lookupLeadByPhone(customerPhone)
                    .or(() -> clueIntegrationService.createLeadForNewCustomer(
                            customerPhone, customerPhone, assignedAgent, activityRowId));
            lead.map(com.example.aitmk.model.domain.LeadRecord::getRowId)
                    .filter(rowId -> rowId != null && !rowId.isBlank())
                    .ifPresent(rowId -> clueIntegrationService.updateLeadOnAssignment(rowId, assignedAgent, activityRowId));
        } catch (Exception ex) {
            log.warn("CRM lead sync failed after online assignment. customer={}, agent={}",
                    customerPhone, assignedAgent, ex);
        }
    }
}
