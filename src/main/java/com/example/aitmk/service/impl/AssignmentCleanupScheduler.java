package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.AssignmentRecordRepository;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 30 天僵尸分配清理定时任务。
 * 扫描所有 SERVING 状态的分配，若客户最后消息距今超过 30 天，
 * 关闭该分配和关联会话，释放坐席绑定关系。
 * 与 webhook Step 1 的 >30d 判断互补：
 * - Step 1 在客户主动发消息时即时清理
 * - 本定时任务清理不再发消息的僵尸分配
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AssignmentCleanupScheduler {

    private final AssignmentRecordRepository assignments;
    private final ResourceRepository resources;
    private final ConversationRepository conversations;
    private final CrmOpenApiService crmOpenApiService;

    private static final long THIRTY_DAYS_HOURS = 24L * 30L;

    @Scheduled(fixedDelay = 3600_000L, initialDelay = 300_000L)
    @Transactional
    public void scanExpiredAssignments() {
        List<AssignmentRecordEntity> serving = assignments.findByStatus(AssignmentStatus.SERVING);
        Instant threshold = Instant.now().minus(Duration.ofHours(THIRTY_DAYS_HOURS));

        for (AssignmentRecordEntity a : serving) {
            ResourceEntity r = resources.findById(a.getResourceId()).orElse(null);
            if (r == null || r.getLastCustomerMessageAt() == null) continue;
            if (r.getLastCustomerMessageAt().isAfter(threshold)) continue;

            // 关闭分配
            a.setStatus(AssignmentStatus.CLOSED);
            a.setReplyable(false);
            a.setClosedAt(Instant.now());
            a.setCloseReason("30_DAY_EXPIRY");
            assignments.save(a);

            // 释放 Resource
            r.setAssignedAgentId(null);
            r.setAssignedAt(null);
            r.setResourceStatus(ResourceStatus.PENDING_ASSIGNMENT);
            r.setLastCustomerMessageAt(null);
            resources.save(r);

            // 关闭关联会话
            conversations.findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(
                    r.getId(), List.of(ConversationStatus.ACTIVE, ConversationStatus.AI_ACTIVE, ConversationStatus.HUMAN_ACTIVE)
            ).ifPresent(c -> {
                c.setStatus(ConversationStatus.CLOSED);
                c.setClosedAt(Instant.now());
                c.setCloseReason("30_DAY_EXPIRY");
                conversations.save(c);
            });

            // 同步 CRM
            try {
                crmOpenApiService.closeServingAssignment(r.getCustomerPhone());
            } catch (Exception ex) {
                log.warn("Cleanup30d: CRM close assignment failed. customer={}", r.getCustomerPhone(), ex);
            }

            log.info("Cleanup30d: expired assignment closed. customer={}, agent={}, lastMessage={}",
                    r.getCustomerPhone(), a.getAgentId(), r.getLastCustomerMessageAt());
        }
    }
}
