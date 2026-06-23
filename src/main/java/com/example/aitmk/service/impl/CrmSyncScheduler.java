package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.CrmSyncTaskEntity;
import com.example.aitmk.repository.CrmSyncTaskRepository;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * CRM 异步同步定时任务。
 * 当主流程的 CRM 同步调用失败时，写入 crm_sync_task 表，
 * 本定时任务每隔 10 秒扫描并重试失败的任务。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class CrmSyncScheduler {

    private final CrmSyncTaskRepository crmSyncTaskRepository;
    private final CrmOpenApiService crmOpenApiService;
    private final ObjectMapper objectMapper;

    private static final List<String> PENDING_STATUSES = List.of("PENDING", "FAILED");
    private static final int BATCH_SIZE = 20;

    @Scheduled(fixedDelay = 10_000L, initialDelay = 15_000L)
    @Transactional
    public void processPendingTasks() {
        List<CrmSyncTaskEntity> tasks = crmSyncTaskRepository.lockPending(
                PENDING_STATUSES, PageRequest.of(0, BATCH_SIZE));

        if (tasks.isEmpty()) {
            return;
        }

        log.info("CRM sync scheduler processing {} pending tasks", tasks.size());

        for (CrmSyncTaskEntity task : tasks) {
            try {
                boolean success = executeTask(task);
                if (success) {
                    crmSyncTaskRepository.delete(task);
                    log.info("CRM sync task completed and removed. taskId={}, eventType={}",
                            task.getId(), task.getEventType());
                } else {
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setStatus("FAILED");
                    task.setLastError("Execution returned false/flaky");
                    crmSyncTaskRepository.save(task);
                    if (task.getRetryCount() >= task.getMaxRetries()) {
                        log.warn("CRM sync task exhausted retries. taskId={}, eventType={}, aggregateId={}",
                                task.getId(), task.getEventType(), task.getAggregateId());
                    }
                }
            } catch (Exception ex) {
                task.setRetryCount(task.getRetryCount() + 1);
                task.setStatus("FAILED");
                task.setLastError(truncate(ex.getMessage(), 1000));
                crmSyncTaskRepository.save(task);
                if (task.getRetryCount() >= task.getMaxRetries()) {
                    log.error("CRM sync task failed permanently. taskId={}, eventType={}",
                            task.getId(), task.getEventType(), ex);
                } else {
                    log.warn("CRM sync task failed, will retry. taskId={}, attempts={}/{}",
                            task.getId(), task.getRetryCount(), task.getMaxRetries(), ex);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean executeTask(CrmSyncTaskEntity task) {
        String eventType = task.getEventType();
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(task.getPayloadJson(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize payload for task {}", task.getId());
            return false;
        }

        return switch (eventType) {
            case "ADD_CHAT_RECORD" -> retryAddChatRecord(params);
            case "ADD_ASSIGNMENT" -> retryAddAssignment(params);
            case "UPDATE_LOGIN_STATUS" -> retryUpdateLoginStatus(params);
            default -> {
                log.warn("Unknown CRM sync event type: {}. taskId={}", eventType, task.getId());
                yield false;
            }
        };
    }

    private boolean retryAddChatRecord(Map<String, Object> params) {
        String businessAccountId = str(params, "businessAccountId");
        String customerPhone = str(params, "customerPhone");
        String agentAccountRowId = str(params, "agentAccountRowId");
        String sender = str(params, "sender");
        String message = str(params, "message");
        if (!StringUtils.hasText(customerPhone) || !StringUtils.hasText(message)) {
            return false;
        }
        return crmOpenApiService.addChatRecord(businessAccountId, customerPhone, agentAccountRowId, sender, message);
    }

    private boolean retryAddAssignment(Map<String, Object> params) {
        String customerPhone = str(params, "customerPhone");
        String agentAccountRowId = str(params, "agentAccountRowId");
        String serviceStatus = str(params, "serviceStatus");
        if (!StringUtils.hasText(customerPhone) || !StringUtils.hasText(agentAccountRowId)) {
            return false;
        }
        return crmOpenApiService.addAssignmentRecord(customerPhone, agentAccountRowId,
                StringUtils.hasText(serviceStatus) ? serviceStatus : "服务中");
    }

    private boolean retryUpdateLoginStatus(Map<String, Object> params) {
        String loginRecordRowId = str(params, "loginRecordRowId");
        String status = str(params, "status");
        if (!StringUtils.hasText(loginRecordRowId) || !StringUtils.hasText(status)) {
            return false;
        }
        return crmOpenApiService.updateAgentLoginStatus(loginRecordRowId, status);
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
