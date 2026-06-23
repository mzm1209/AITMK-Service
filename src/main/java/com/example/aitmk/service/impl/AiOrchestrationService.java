package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.*;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import com.example.aitmk.util.AiReplyParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 接待状态机。
 * 管理从客户首次消息到坐席接手的多轮 AI 交互流程。
 * 区分工作时间和下班时间的不同行为模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiOrchestrationService {

    private final AiService aiService;
    private final ConversationRepository conversationRepository;
    private final ResourceRepository resourceRepository;
    private final SendMessageService sendMessageService;
    private final MessagePersistenceService messagePersistenceService;
    private final AgentDispatchService agentDispatchService;
    private final WorkTimeService workTimeService;
    private final RealtimeEventService realtimeEventService;
    private final RealtimePayloadFactory realtimePayloadFactory;
    private final AutoReplyScriptCacheService autoReplyScriptCacheService;
    private final ObjectMapper objectMapper;

    @Value("${whatsapp.default-business-account-id:}")
    private String defaultBusinessAccountId;

    @Value("${ai.prompt.welcome:}")
    private String welcomePrompt;

    @Value("${ai.prompt.after-hours-collect:}")
    private String afterHoursCollectPrompt;

    private volatile Map<String, String> learningCenterMapping = new HashMap<>();

    @Value("${ai.prompt.learning-center-mapping:{}}")
    public void setLearningCenterMapping(String json) {
        if (!StringUtils.hasText(json) || "{}".equals(json.trim())) {
            learningCenterMapping = new HashMap<>();
            return;
        }
        try {
            learningCenterMapping = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse learning-center-mapping: {}", json);
            learningCenterMapping = new HashMap<>();
        }
    }

    /**
     * 处理客户消息并决定 AI 的下一步动作。
     * 由 WhatsAppWebhookServiceImpl 在未分配和已分配但坐席离线场景调用。
     */
    @Transactional
    public void orchestrate(
            String businessAccountId,
            String customerPhone,
            String customerContent,
            ConversationEntity conversation,
            ResourceEntity resource
    ) {
        String baId = StringUtils.hasText(businessAccountId) ? businessAccountId : defaultBusinessAccountId;
        AiState current = conversation.getAiState() == null ? AiState.NONE : conversation.getAiState();

        log.info("AI orchestrate start. customer={}, aiState={}, isWorkingTime={}, content={}",
                customerPhone, current, workTimeService.isWorkingTimeNow(),
                customerContent != null && customerContent.length() > 80
                        ? customerContent.substring(0, 80) + "..." : customerContent);

        switch (current) {
            case NONE -> handleInitialState(baId, customerPhone, customerContent, conversation, resource);
            case WELCOME_SENT -> handleWelcomeResponse(baId, customerPhone, customerContent, conversation, resource);
            case COLLECTING_INFO -> handleAfterHoursResponse(baId, customerPhone, customerContent, conversation, resource);
            case WAITING_CENTER -> handleWaitingCenter(baId, customerPhone, customerContent, conversation, resource);
            case TRANSFERRED -> handleTransferredState(baId, customerPhone, customerContent, conversation, resource);
        }
    }

    /**
     * 初始状态（NONE）：
     * 工作时间 → 发送欢迎语，设置 WELCOME_SENT
     * 下班时间 → 发送信息收集提示，设置 COLLECTING_INFO
     */
    private void handleInitialState(
            String baId, String customerPhone, String customerContent,
            ConversationEntity conversation, ResourceEntity resource
    ) {
        if (workTimeService.isWorkingTimeNow()) {
            sendWelcome(baId, customerPhone, conversation);
        } else {
            collectAfterHoursInfo(baId, customerPhone, customerContent, conversation);
        }
    }

    /**
     * 发送欢迎语 + 学习中心选项（初始状态）。
     * 设置 AiState = WELCOME_SENT。
     */
    public void sendWelcome(String businessAccountId, String customerPhone, ConversationEntity conversation) {
        String welcomeText = welcomePrompt;
        if (!StringUtils.hasText(welcomeText)) {
            welcomeText = autoReplyScriptCacheService.firstReplyScript();
        }
        if (!StringUtils.hasText(welcomeText)) {
            log.warn("No welcome prompt configured, fallback to AI generate. customer={}", customerPhone);
            String aiRaw = aiService.chat("新客户首次咨询，请生成友好的欢迎语并询问客户想了解哪个学习中心。");
            welcomeText = AiReplyParser.parseAnswer(aiRaw);
            if (!StringUtils.hasText(welcomeText)) {
                log.warn("AI welcome generation returned empty. customer={}", customerPhone);
                return;
            }
        }

        sendAiReply(businessAccountId, customerPhone, welcomeText, conversation, null);

        conversation.setAiState(AiState.WELCOME_SENT);
        conversationRepository.save(conversation);

        // Look up resource after sending welcome (caller may pass null)
        ResourceEntity resourceEntity = resourceRepository.findById(conversation.getResourceId()).orElse(null);
        if (resourceEntity != null) {
            resourceEntity.setResourceStatus(ResourceStatus.AI_SERVING);
            resourceRepository.save(resourceEntity);
        }

        log.info("AI welcome sent. customer={}, aiState={}", customerPhone, AiState.WELCOME_SENT);
    }

    /**
     * 客户对欢迎语的回复处理：
     * 判断是否选择了学习中心，若选择了则尝试分配。
     */
    private void handleWelcomeResponse(
            String baId, String customerPhone, String customerContent,
            ConversationEntity conversation, ResourceEntity resource
    ) {
        // 让 AI 分析客户是否选择了学习中心及相关意向信息
        String aiContext = "You already presented study center options. Customer replied: " + customerContent + "\nPlease determine if the customer selected a study center (options are CenterA, CenterB, CenterC)." + "\nIf yes, reply: CENTER_SELECTED: <center-name>. Otherwise, continue guiding the customer.";
        String aiRaw = aiService.chat(aiContext);
        String aiAnswer = AiReplyParser.parseAnswer(aiRaw);

        if (aiAnswer != null && (aiAnswer.contains("CENTER_SELECTED:") || aiAnswer.matches(".*中心[ABC].*"))) {
            handleLearningCenterChoice(baId, customerPhone, customerContent, conversation);
        } else {
            sendAiReply(baId, customerPhone, aiAnswer, conversation, null);
        }
    }

    /**
     * 处理客户对学习中心的选择。
     * 分配坐席或标记等待。
     */
    public void handleLearningCenterChoice(
            String businessAccountId, String customerPhone, String customerContent,
            ConversationEntity conversation
    ) {
        boolean hasOnlineAgent = agentDispatchService.hasOnlineAgent();

        if (hasOnlineAgent) {
            agentDispatchService.assignIfAbsent(customerPhone).ifPresentOrElse(agentRowId -> {
                conversation.setAiState(AiState.TRANSFERRED);
                conversation.setAssignedAgentId(agentRowId);
                conversationRepository.save(conversation);
                log.info("Customer transferred to agent. customer={}, agent={}", customerPhone, agentRowId);
            }, () -> {
                conversation.setAiState(AiState.WAITING_CENTER);
                conversationRepository.save(conversation);
                log.info("Customer moved to waiting center (no agent available). customer={}", customerPhone);
            });
        } else {
            conversation.setAiState(AiState.WAITING_CENTER);
            conversationRepository.save(conversation);
            log.info("Customer moved to waiting center (no online agent). customer={}", customerPhone);
        }

        realtimeEventService.append("CONVERSATION_UPDATED", "CONVERSATION", conversation.getId(),
                conversation.getResourceId(), conversation.getId(), conversation.getAssignedAgentId(),
                conversation.getVersion(), realtimePayloadFactory.conversation(conversation, conversation.getAssignedAgentId()));
    }

    /**
     * 下班时间收集客户信息：地址/学习中心/科目/年龄/预约时间。
     * 通过 AI 判断信息是否收集完整。
     */
    public void collectAfterHoursInfo(
            String businessAccountId, String customerPhone, String customerContent,
            ConversationEntity conversation
    ) {
        String collectPrompt = StringUtils.hasText(afterHoursCollectPrompt)
                ? afterHoursCollectPrompt
                : "感谢您的咨询。当前为非工作时间，请留下您的信息，我们将在上班后第一时间联系您。";

        String aiContext = collectPrompt + "\n\nCustomer message: " + customerContent + "\nPlease analyze if the customer provided sufficient info (such as study center, subject, age, contact time)." + "\nIf info is complete, reply: INFO_COMPLETE. Otherwise, ask for specific details.";
        String aiRaw = aiService.chat(aiContext);
        String aiAnswer = AiReplyParser.parseAnswer(aiRaw);

        if (aiAnswer != null && aiAnswer.contains("INFO_COMPLETE")) {
            conversation.setAiState(AiState.TRANSFERRED);
            conversationRepository.save(conversation);
            log.info("After-hours info collection complete. customer={}", customerPhone);

            sendAiReply(businessAccountId, customerPhone, "感谢您提供的信息！我们将在工作时间尽快与您联系。", conversation, null);
        } else {
            conversation.setAiState(AiState.COLLECTING_INFO);
            conversationRepository.save(conversation);

            sendAiReply(businessAccountId, customerPhone, aiAnswer, conversation, null);
        }
    }

    /**
     * 处理下班期间客户的信息回复（多轮收集）。
     */
    private void handleAfterHoursResponse(
            String baId, String customerPhone, String customerContent,
            ConversationEntity conversation, ResourceEntity resource
    ) {
        collectAfterHoursInfo(baId, customerPhone, customerContent, conversation);
    }

    /**
     * 等待中心状态：客户在排队，回复排队信息。
     */
    private void handleWaitingCenter(
            String baId, String customerPhone, String customerContent,
            ConversationEntity conversation, ResourceEntity resource
    ) {
        String aiRaw = aiService.chat("You are in the waiting center queue. Customer said: " + customerContent + "\nPlease politely inform the customer that agents are currently busy and they are queued for a response.");
        String aiAnswer = AiReplyParser.parseAnswer(aiRaw);
        if (StringUtils.hasText(aiAnswer)) {
            sendAiReply(baId, customerPhone, aiAnswer, conversation, null);
        }
    }

    /**
     * 已转移状态：通常由人工坐席处理，AI 不再主动干预。
     */
    private void handleTransferredState(
            String baId, String customerPhone, String customerContent,
            ConversationEntity conversation, ResourceEntity resource
    ) {
        // AI 不做自动回复，记录轨迹
        log.debug("Message in transferred state. customer={}, aiState=TRANSFERRED", customerPhone);
    }

    /**
     * 统一发送 AI 回复并持久化。
     */
    private void sendAiReply(String baId, String customerPhone, String answer,
                              ConversationEntity conversation, ResourceEntity resource) {
        if (!StringUtils.hasText(answer)) {
            return;
        }
        try {
            long localMessageId = messagePersistenceService.createOutgoing(customerPhone, baId,
                    SenderType.AI, null, null, MessageType.TEXT, answer, null, null, null);

            sendMessageService.sendTextMessage(baId, customerPhone, answer, localMessageId);

            log.info("AI reply sent. customer={}, content={}", customerPhone,
                    answer.length() > 60 ? answer.substring(0, 60) + "..." : answer);
        } catch (Exception ex) {
            log.error("Send AI reply failed. customer={}", customerPhone, ex);
        }
    }
}
