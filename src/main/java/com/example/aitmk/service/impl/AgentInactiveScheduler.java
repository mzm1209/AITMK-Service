package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AiService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.SendMessageService;
import com.example.aitmk.util.AiReplyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 坐席无操作自动离线。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentInactiveScheduler {

    private final AgentSessionActivityService sessionActivityService;
    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;
    private final ChatHistoryService chatHistoryService;
    private final AiService aiService;
    private final SendMessageService sendMessageService;

    @Value("${agent.inactive.minutes:3}")
    private int inactiveMinutes;

    @Value("${whatsapp.default-business-account-id:1019964791197772}")
    private String defaultBusinessAccountId;

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void scanInactiveAgent() {
        sessionActivityService.scanInactive(inactiveMinutes).forEach(state -> {
            replyPendingCustomerMessagesWithAi(state.agentRowId());
            agentDispatchService.markOffline(state.agentRowId());
            if (state.loginRecordRowId() != null && !state.loginRecordRowId().isBlank()) {
                crmOpenApiService.updateAgentLoginStatus(state.loginRecordRowId(), "离线");
            }
            log.info("Agent auto-offline by inactivity. agent={}, thresholdMinutes={}", state.agentRowId(), inactiveMinutes);
        });
    }

    /**
     * 坐席自动离线时：如果该坐席负责会话的最后一条消息来自客户（坐席尚未回复），由 AI 自动补回复。
     */
    private void replyPendingCustomerMessagesWithAi(String offlineAgentRowId) {
        Map<String, String> assignments = agentDispatchService.assignmentsSnapshot();
        assignments.forEach((customerPhone, agentRowId) -> {
            if (!offlineAgentRowId.equals(agentRowId)) {
                return;
            }
            List<com.example.aitmk.model.domain.ChatMessageRecord> messages = chatHistoryService.listMessages(customerPhone);
            if (messages.isEmpty()) {
                return;
            }
            com.example.aitmk.model.domain.ChatMessageRecord last = messages.get(messages.size() - 1);
            if (!"customer".equals(last.getSender())) {
                return;
            }

            try {
                String aiRaw = aiService.chat(last.getMessage());
                String aiText = AiReplyParser.parseAnswer(aiRaw);
                chatHistoryService.recordAiReply(customerPhone, aiText);
                sendMessageService.sendTextMessage(defaultBusinessAccountId, customerPhone, aiText);
                crmOpenApiService.addChatRecord(defaultBusinessAccountId, customerPhone, offlineAgentRowId, "AI", aiText);
                log.info("Auto AI replied for offline agent pending customer message. agent={}, customer={}", offlineAgentRowId, customerPhone);
            } catch (Exception ex) {
                log.error("Auto AI reply failed when agent inactive. agent={}, customer={}", offlineAgentRowId, customerPhone, ex);
            }
        });
    }
}
