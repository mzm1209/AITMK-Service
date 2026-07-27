package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.impl.ClueIntegrationService;
import com.example.aitmk.model.domain.LeadRecord;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.WorkTimeService;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 下班时间信息收集完成后，扫描到上班时间时自动分配坐席。
 * 检查因素：
 * - 当前已进入工作时间
 * - 会话 AiState = COLLECTING_INFO
 * - 对应的资源状态为 PENDING_ASSIGNMENT
 * - 有在线坐席
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AfterHoursTransitionScheduler {

    private final EntityManager em;
    private final ConversationRepository conversationRepository;
    private final ResourceRepository resourceRepository;
    private final AgentDispatchService agentDispatchService;
    private final ClueIntegrationService clueIntegrationService;
    private final WorkTimeService workTimeService;
    private final RealtimeEventService realtimeEventService;
    private final RealtimePayloadFactory realtimePayloadFactory;

    @Scheduled(fixedDelay = 60_000L, initialDelay = 30_000L)
    @Transactional
    public void scanAfterHoursTransition() {
        if (!workTimeService.isWorkingTimeNow()) {
            return;
        }

        if (!agentDispatchService.hasOnlineAgent()) {
            log.debug("AfterHoursTransition: working time but no online agents");
            return;
        }

        Query query = em.createQuery(
                "select c from ConversationEntity c where c.aiState = :aiState order by c.createdAt asc");
        query.setParameter("aiState", AiState.COLLECTING_INFO);

        @SuppressWarnings("unchecked")
        List<ConversationEntity> candidates = query.getResultList();

        if (candidates.isEmpty()) {
            return;
        }

        log.info("AfterHoursTransition scan found {} candidate conversations", candidates.size());

        for (ConversationEntity conversation : candidates) {
            try {
                // 检查资源状态是否为 PENDING_ASSIGNMENT
                ResourceEntity resource = resourceRepository.findByCustomerPhoneForUpdate(
                        conversation.getCustomerPhone()).orElse(null);
                if (resource == null || resource.getResourceStatus() != ResourceStatus.PENDING_ASSIGNMENT) {
                    continue;
                }

                agentDispatchService.assignIfAbsent(conversation.getCustomerPhone()).ifPresentOrElse(agentRowId -> {
                    conversation.setAiState(AiState.TRANSFERRED);
                    conversation.setAssignedAgentId(agentRowId);
                    conversationRepository.save(conversation);
                    log.info("After-hours transition: assigned agent. customer={}, agent={}",
                            conversation.getCustomerPhone(), agentRowId);
                    try {
                        String phone = conversation.getCustomerPhone();
                        String activityRowId = clueIntegrationService.resolveActivityRowIdForCustomer(phone).orElse(null);
                        var leadOpt = clueIntegrationService.lookupLeadByPhone(phone);
                        if (leadOpt.isPresent() && StringUtils.hasText(leadOpt.get().getRowId())) {
                            clueIntegrationService.updateLeadOnAssignment(leadOpt.get().getRowId(), agentRowId, activityRowId);
                            log.info("Lead updated after after-hours assignment. customer={}, agent={}", phone, agentRowId);
                        } else {
                            String contactName = resource.getCustomerName();
                            clueIntegrationService.createLeadForNewCustomer(phone, contactName, agentRowId, activityRowId);
                            log.info("Lead created after after-hours assignment. customer={}, agent={}", phone, agentRowId);
                        }
                    } catch (Exception ex) {
                        log.warn("Clue integration failed in after-hours assignment. customer={}, agent={}",
                                conversation.getCustomerPhone(), agentRowId, ex);
                    }

                    realtimeEventService.append("CONVERSATION_UPDATED", "CONVERSATION",
                            conversation.getId(), conversation.getResourceId(), conversation.getId(),
                            agentRowId, conversation.getVersion(),
                            realtimePayloadFactory.conversation(conversation, agentRowId));
                }, () -> log.warn("AfterHoursTransition: assign failed for customer={}",
                        conversation.getCustomerPhone()));
            } catch (Exception ex) {
                log.error("AfterHoursTransition failed for conversationId={}, customer={}",
                        conversation.getId(), conversation.getCustomerPhone(), ex);
            }
        }
    }
}
