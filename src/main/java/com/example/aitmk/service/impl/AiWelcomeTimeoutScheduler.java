package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ConversationEntity;
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

import java.time.Instant;
import java.util.List;

/**
 * 扫描 AiState=WELCOME_SENT 且超过 60 秒无回复的会话：
 * - 工作时间且有在线坐席 → 分配坐席，设置 TRANSFERRED
 * - 无在线坐席 → 设置 WAITING_CENTER
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "integration.schedulers-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AiWelcomeTimeoutScheduler {

    private final EntityManager em;
    private final ConversationRepository conversationRepository;
    private final ResourceRepository resourceRepository;
    private final AgentDispatchService agentDispatchService;
    private final ClueIntegrationService clueIntegrationService;
    private final WorkTimeService workTimeService;
    private final RealtimeEventService realtimeEventService;
    private final RealtimePayloadFactory realtimePayloadFactory;

    private static final int WELCOME_TIMEOUT_SECONDS = 60;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    @Transactional
    public void scanWelcomeTimeout() {
        Instant timeoutThreshold = Instant.now().minusSeconds(WELCOME_TIMEOUT_SECONDS);

        Query query = em.createQuery(
                "select c from ConversationEntity c where c.aiState = :aiState and c.lastMessageAt < :threshold");
        query.setParameter("aiState", AiState.WELCOME_SENT);
        query.setParameter("threshold", timeoutThreshold);

        @SuppressWarnings("unchecked")
        List<ConversationEntity> expired = query.getResultList();

        if (expired.isEmpty()) {
            return;
        }

        log.info("Welcome timeout scan found {} expired conversations", expired.size());

        for (ConversationEntity conversation : expired) {
            try {
                handleTimeout(conversation);
            } catch (Exception ex) {
                log.error("Handle welcome timeout failed. conversationId={}, customer={}",
                        conversation.getId(), conversation.getCustomerPhone(), ex);
            }
        }
    }

    private void handleTimeout(ConversationEntity conversation) {
        boolean hasOnlineAgent = agentDispatchService.hasOnlineAgent();
        boolean isWorkingTime = workTimeService.isWorkingTimeNow();

        if (isWorkingTime && hasOnlineAgent) {
            // 工作时间且有在线坐席 → 尝试分配
            agentDispatchService.assignIfAbsent(conversation.getCustomerPhone()).ifPresentOrElse(agentRowId -> {
                conversation.setAiState(AiState.TRANSFERRED);
                conversation.setAssignedAgentId(agentRowId);
                conversationRepository.save(conversation);
                log.info("Welcome timeout: assigned to agent. customer={}, agent={}",
                        conversation.getCustomerPhone(), agentRowId);
                try {
                    String phone = conversation.getCustomerPhone();
                    var leadOpt = clueIntegrationService.lookupLeadByPhone(phone);
                    if (leadOpt.isPresent() && StringUtils.hasText(leadOpt.get().getRowId())) {
                        clueIntegrationService.updateLeadOnAssignment(leadOpt.get().getRowId(), agentRowId);
                        log.info("Lead updated after welcome-timeout assignment. customer={}, agent={}", phone, agentRowId);
                    } else {
                        String contactName = resourceRepository.findByCustomerPhone(phone)
                                .map(r -> r.getCustomerName()).orElse(null);
                        clueIntegrationService.createLeadForNewCustomer(phone, contactName, agentRowId);
                        log.info("Lead created after welcome-timeout assignment. customer={}, agent={}", phone, agentRowId);
                    }
                } catch (Exception ex) {
                    log.warn("Clue integration failed in welcome-timeout assignment. customer={}, agent={}",
                            conversation.getCustomerPhone(), agentRowId, ex);
                }

                realtimeEventService.append("CONVERSATION_UPDATED", "CONVERSATION",
                        conversation.getId(), conversation.getResourceId(), conversation.getId(),
                        agentRowId, conversation.getVersion(),
                        realtimePayloadFactory.conversation(conversation, agentRowId));
            }, () -> {
                conversation.setAiState(AiState.WAITING_CENTER);
                conversationRepository.save(conversation);
                log.info("Welcome timeout: no agent available, moved to waiting. customer={}",
                        conversation.getCustomerPhone());
            });
        } else {
            conversation.setAiState(AiState.WAITING_CENTER);
            conversationRepository.save(conversation);
            log.info("Welcome timeout: moved to waiting (offline={}). customer={}",
                    !isWorkingTime, conversation.getCustomerPhone());
        }
    }
}
