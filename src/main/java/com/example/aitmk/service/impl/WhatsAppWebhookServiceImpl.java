package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.WhatsAppMessage;
import com.example.aitmk.model.webhook.WhatsAppWebhookRequest;
import com.example.aitmk.parser.WhatsAppMessageParser;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AgentPushService;
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

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookServiceImpl implements WhatsAppWebhookService {

    private final ObjectMapper objectMapper;
    private final AiService aiService;
    private final ChatHistoryService chatHistoryService;
    private final SendMessageService sendService;
    private final AgentDispatchService agentDispatchService;
    private final AgentPushService agentPushService;
    private final CrmOpenApiService crmOpenApiService;

    @Override
    @Async
    public void process(String payload) {
        try {
            WhatsAppWebhookRequest request = objectMapper.readValue(payload, WhatsAppWebhookRequest.class);
            if (request.getEntry() == null) {
                log.info("Webhook ignored: entry is null");
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

                    String finalBusinessAccountId = businessAccountId;
                    change.getValue().getMessages().forEach(message -> processOneMessage(finalBusinessAccountId, message));
                });
            });
        } catch (Exception e) {
            log.error("Webhook processing error", e);
        }
    }

    private void processOneMessage(String businessAccountId, com.example.aitmk.model.webhook.Message message) {
        try {
            WhatsAppMessage parsed = WhatsAppMessageParser.parse(message);
            String customerPhone = parsed.getFrom();
            String customerText = parsed.getText() == null ? "" : parsed.getText();

            if (customerPhone == null || customerPhone.isBlank()) {
                log.warn("Skip message because customer phone is blank");
                return;
            }

            // 1) 本地状态先更新：保证第三方调用异常不会影响本地缓存完整性
            chatHistoryService.recordCustomerMessage(customerPhone, customerText);
            log.info("Local history recorded for customer message. customer={}", customerPhone);

            String assignedAgent = agentDispatchService.getAssignedAgent(customerPhone).orElse(null);

            // 2) CRM记录失败不影响主流程
            try {
                crmOpenApiService.addChatRecord(businessAccountId, customerPhone, assignedAgent, "客户", customerText);
            } catch (Exception ex) {
                log.error("CRM add customer chat record failed. customer={}", customerPhone, ex);
            }

            ChatMessageRecord customerRecord = ChatMessageRecord.builder()
                    .customerId(customerPhone)
                    .sender("customer")
                    .message(customerText)
                    .timestamp(Instant.now())
                    .build();

            if (assignedAgent != null && !assignedAgent.isBlank()) {
                // 已有坐席接待：停止 AI 自动回复，仅推送给坐席
                try {
                    agentPushService.pushNewMessage(assignedAgent, customerPhone, customerRecord);
                    log.info("Pushed new customer message to assigned agent. agent={}, customer={}", assignedAgent, customerPhone);
                } catch (Exception ex) {
                    log.error("Push new message to assigned agent failed. agent={}, customer={}", assignedAgent, customerPhone, ex);
                }
                return;
            }

            // 3) 未分配客户：先保证本地队列状态正确
            boolean hasOnlineAgent = agentDispatchService.hasOnlineAgent();
            if (!hasOnlineAgent) {
                agentDispatchService.markUnassigned(customerPhone);
                log.info("Customer marked pending because no online agent. customer={}", customerPhone);
            }

            // 4) AI流程（失败不影响本地缓存）
            try {
                String aiReplyJson = aiService.chat(customerText);
                String aiAnswer = AiReplyParser.parseAnswer(aiReplyJson);

                chatHistoryService.recordAiReply(customerPhone, aiAnswer);
                log.info("Local history recorded for AI reply. customer={}", customerPhone);

                sendService.sendTextMessage(businessAccountId, customerPhone, aiAnswer);

                try {
                    crmOpenApiService.addChatRecord(businessAccountId, customerPhone, null, "AI", aiAnswer);
                } catch (Exception ex) {
                    log.error("CRM add AI chat record failed. customer={}", customerPhone, ex);
                }
            } catch (Exception ex) {
                log.error("AI reply flow failed. customer={}", customerPhone, ex);
            }

            // 5) 若当前有在线坐席，始终尝试本地分配（不受AI/CRM异常影响）
            if (hasOnlineAgent) {
                agentDispatchService.assignIfAbsent(customerPhone).ifPresentOrElse(agentRowId -> {
                    log.info("Customer assigned locally. customer={}, agent={}", customerPhone, agentRowId);
                    try {
                        crmOpenApiService.addAssignmentRecord(customerPhone, agentRowId, "服务中");
                    } catch (Exception ex) {
                        log.error("CRM add assignment failed. customer={}, agent={}", customerPhone, agentRowId, ex);
                    }

                    try {
                        agentPushService.pushHistory(agentRowId, customerPhone, chatHistoryService.listMessages(customerPhone));
                        log.info("Pushed full history to agent after assignment. customer={}, agent={}", customerPhone, agentRowId);
                    } catch (Exception ex) {
                        log.error("Push history to agent failed. customer={}, agent={}", customerPhone, agentRowId, ex);
                    }
                }, () -> {
                    // 理论上 hasOnlineAgent=true 时不会进入，但这里兜底保证本地队列正确
                    agentDispatchService.markUnassigned(customerPhone);
                    log.warn("Assign failed unexpectedly, fallback mark pending. customer={}", customerPhone);
                });
            }
        } catch (Exception ex) {
            log.error("Process one message failed unexpectedly", ex);
        }
    }
}
