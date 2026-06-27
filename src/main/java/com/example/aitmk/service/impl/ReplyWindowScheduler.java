package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.AssignmentStatus;
import com.example.aitmk.repository.AssignmentRecordRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 24 小时回复窗口定时任务。
 * 扫描所有 SERVING 状态且 replyable=true 的分配，若客户最后消息距今超过 24 小时，
 * 则在 IM 数据库和 CRM 中将该分配标记为不可回复（replyable=false）。
 * 分配关系本身保持不变，坐席仍然绑定该客户。
 * 当客户再次发消息时（webhook Step 1），自动恢复 replyable=true。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReplyWindowScheduler {

    private final AssignmentRecordRepository assignments;
    private final ResourceRepository resources;
    private final CrmOpenApiService crmOpenApiService;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 120_000L)
    @Transactional
    public void scanReplyWindow() {
        List<AssignmentRecordEntity> serving = assignments.findByStatus(AssignmentStatus.SERVING);
        Instant threshold = Instant.now().minus(Duration.ofHours(24));

        for (AssignmentRecordEntity a : serving) {
            if (!a.isReplyable()) continue;
            ResourceEntity r = resources.findById(a.getResourceId()).orElse(null);
            if (r == null || r.getLastCustomerMessageAt() == null) continue;
            if (r.getLastCustomerMessageAt().isAfter(threshold)) continue;

            // 标记 IM 数据库不可回复
            a.setReplyable(false);
            assignments.save(a);

            // 同步 CRM
            try {
                crmOpenApiService.updateServingAssignmentReplyable(r.getCustomerPhone(), false);
            } catch (Exception ex) {
                log.warn("ReplyWindow: CRM update replyable=false failed. customer={}", r.getCustomerPhone(), ex);
            }

            log.info("ReplyWindow: marked unreplyable after 24h. customer={}, agent={}, lastMessage={}",
                    r.getCustomerPhone(), a.getAgentId(), r.getLastCustomerMessageAt());
        }
    }
}
