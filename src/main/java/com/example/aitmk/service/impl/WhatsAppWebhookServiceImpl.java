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
import org.springframework.util.StringUtils;

import java.time.Duration;
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
            String rawCustomerPhone = parsed.getFrom();
            String customerPhone = normalizeCustomerPhone(rawCustomerPhone);
            String customerContent = buildCustomerContent(parsed);

            if (!StringUtils.hasText(customerPhone)) {
                log.warn("Skip message because customer phone is blank. rawPhone={}", rawCustomerPhone);
                return;
            }

            Instant now = Instant.now();
            Instant lastCustomerBefore = chatHistoryService.lastCustomerMessageTime(customerPhone).orElse(null);

            log.info("Webhook message received. rawPhone={}, normalizedPhone={}, type={}, hasText={}, lastCustomerBefore={}",
                    rawCustomerPhone, customerPhone, parsed.getType(), StringUtils.hasText(parsed.getText()), lastCustomerBefore);

            String assignedAgent = lookupAssignedAgent(rawCustomerPhone, customerPhone);

            // 客户超24小时重新发起会话：关闭旧服务中记录并释放本地分配，走重新分配规则
            if (StringUtils.hasText(assignedAgent) && isConversationExpired(lastCustomerBefore, now)) {
                log.info("Conversation expired (>24h), close old assignment before handling new message. customer={}, oldAgent={}",
                        customerPhone, assignedAgent);
                try {
                    boolean closeOk = crmOpenApiService.closeServingAssignment(customerPhone);
                    if (!closeOk) {
                        log.warn("Close serving assignment returned false. customer={}, oldAgent={}", customerPhone, assignedAgent);
                    }
                } catch (Exception ex) {
                    log.error("Close serving assignment failed. customer={}, oldAgent={}", customerPhone, assignedAgent, ex);
                }
                agentDispatchService.unassignCustomer(customerPhone);
                assignedAgent = null;
            }

            // 1) 本地状态先更新：保证第三方调用异常不会影响本地缓存完整性
            chatHistoryService.recordCustomerMessage(customerPhone, customerContent);
            log.info("Local history recorded for customer message. customer={}, content={}", customerPhone, customerContent);

            // 2) CRM记录失败不影响主流程（但必须有明确日志）
            try {
                boolean crmOk = crmOpenApiService.addChatRecord(businessAccountId, customerPhone, assignedAgent, "客户", customerContent);
                if (!crmOk) {
                    log.warn("CRM add customer chat record returned false. customer={}, assignedAgent={}", customerPhone, assignedAgent);
                }
            } catch (Exception ex) {
                log.error("CRM add customer chat record failed. customer={}", customerPhone, ex);
            }

            ChatMessageRecord customerRecord = ChatMessageRecord.builder()
                    .customerId(customerPhone)
                    .sender("customer")
                    .message(customerContent)
                    .timestamp(Instant.now())
                    .build();

            if (StringUtils.hasText(assignedAgent)) {
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

            // 4) 未分配客户走 AI流程（失败不影响本地缓存）
            try {
                String aiReplyJson = aiService.chat(customerContent);
                String aiAnswer = AiReplyParser.parseAnswer(aiReplyJson);

                chatHistoryService.recordAiReply(customerPhone, aiAnswer);
                log.info("Local history recorded for AI reply. customer={}", customerPhone);

                sendService.sendTextMessage(businessAccountId, customerPhone, aiAnswer);

                try {
                    boolean crmOk = crmOpenApiService.addChatRecord(businessAccountId, customerPhone, null, "AI", aiAnswer);
                    if (!crmOk) {
                        log.warn("CRM add AI chat record returned false. customer={}", customerPhone);
                    }
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
                        boolean crmOk = crmOpenApiService.addAssignmentRecord(customerPhone, agentRowId, "服务中");
                        if (!crmOk) {
                            log.warn("CRM add assignment returned false. customer={}, agent={}", customerPhone, agentRowId);
                        }
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
                    agentDispatchService.markUnassigned(customerPhone);
                    log.warn("Assign failed unexpectedly, fallback mark pending. customer={}", customerPhone);
                });
            }
        } catch (Exception ex) {
            log.error("Process one message failed unexpectedly", ex);
        }
    }

    private String lookupAssignedAgent(String rawCustomerPhone, String normalizedCustomerPhone) {
        if (StringUtils.hasText(rawCustomerPhone)) {
            String fromRaw = agentDispatchService.getAssignedAgent(rawCustomerPhone.trim()).orElse(null);
            if (StringUtils.hasText(fromRaw)) {
                return fromRaw;
            }
        }
        return agentDispatchService.getAssignedAgent(normalizedCustomerPhone).orElse(null);
    }

    private String normalizeCustomerPhone(String rawPhone) {
        if (!StringUtils.hasText(rawPhone)) {
            return "";
        }
        return rawPhone.replaceAll("[^0-9]", "");
    }

    private String buildCustomerContent(WhatsAppMessage parsed) {
        if (StringUtils.hasText(parsed.getText())) {
            return parsed.getText().trim();
        }

        String type = StringUtils.hasText(parsed.getType()) ? parsed.getType().trim().toLowerCase() : "unknown";
        StringBuilder sb = new StringBuilder("[").append(type).append("]");

        if (StringUtils.hasText(parsed.getMediaId())) {
            sb.append(" mediaId=").append(parsed.getMediaId());
        }
        if (StringUtils.hasText(parsed.getMediaUrl())) {
            sb.append(" url=").append(parsed.getMediaUrl());
        }
        if ("location".equals(type) && parsed.getLatitude() != null && parsed.getLongitude() != null) {
            sb.append(" lat=").append(parsed.getLatitude()).append(" lng=").append(parsed.getLongitude());
        }

        return sb.toString();
    }

    private boolean isConversationExpired(Instant lastCustomerBefore, Instant now) {
        if (lastCustomerBefore == null) {
            return false;
        }
        return Duration.between(lastCustomerBefore, now).toHours() > 24;
    }
}
