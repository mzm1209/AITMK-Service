package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.WhatsAppMessage;
import com.example.aitmk.model.webhook.WhatsAppWebhookRequest;
import com.example.aitmk.parser.WhatsAppMessageParser;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AiService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.SendMessageService;
import com.example.aitmk.service.WhatsAppWebhookService;
import com.example.aitmk.util.AiReplyParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookServiceImpl implements WhatsAppWebhookService {

    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final ChatHistoryService chatHistoryService;
    private final SendMessageService sendService;
    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;

    @Override
    @Async
    public void process(String payload) {
        try {
            WhatsAppWebhookRequest request = objectMapper.readValue(payload, WhatsAppWebhookRequest.class);
            if (request.getEntry() == null) {
                return;
            }

            request.getEntry().forEach(entry -> {
                if (entry.getChanges() == null) {
                    return;
                }

                entry.getChanges().forEach(change -> {
                    if (change.getValue() == null || change.getValue().getMessages() == null) {
                        return;
                    }

                    String businessAccountId = "1019964791197772";
                    if (change.getValue().getMetadata() != null
                            && change.getValue().getMetadata().getPhone_number_id() != null
                            && !change.getValue().getMetadata().getPhone_number_id().isBlank()) {
                        businessAccountId = change.getValue().getMetadata().getPhone_number_id();
                    }

                    change.getValue().getMessages().forEach(message -> {
                        WhatsAppMessage parsed = WhatsAppMessageParser.parse(message);
                        String customerPhone = parsed.getFrom();
                        String customerText = parsed.getText() == null ? "" : parsed.getText();

                        if (customerPhone == null || customerPhone.isBlank()) {
                            return;
                        }

                        // 记录客户消息（本地 + CRM）
                        chatHistoryService.recordCustomerMessage(customerPhone, customerText);
                        String assignedAgent = agentDispatchService.getAssignedAgent(customerPhone).orElse(null);
                        crmOpenApiService.addChatRecord(businessAccountId, customerPhone, assignedAgent, "客户", customerText);

                        if (assignedAgent != null && !assignedAgent.isBlank()) {
                            // 已有坐席接待，停止 AI 自动回复，仅记录消息并交给坐席处理
                            return;
                        }

                        // 未分配坐席：继续 AI 自动回复
                        String aiReplyJson = aiService.chat(customerText);
                        String aiAnswer = AiReplyParser.parseAnswer(aiReplyJson);

                        chatHistoryService.recordAiReply(customerPhone, aiAnswer);
                        sendService.sendTextMessage(businessAccountId, customerPhone, aiAnswer);
                        crmOpenApiService.addChatRecord(businessAccountId, customerPhone, null, "AI", aiAnswer);

                        // AI 首次完成后，若当前有在线坐席则按登录顺序分配
                        agentDispatchService.assignIfAbsent(customerPhone).ifPresent(agentRowId -> {
                            crmOpenApiService.addAssignmentRecord(customerPhone, agentRowId, "服务中");
                        });
                    });
                });
            });
        } catch (Exception e) {
            log.error("Webhook processing error", e);
        }
    }
}
