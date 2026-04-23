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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** webhook 消息去重：避免 Meta 重试或重复投递导致前端收到重复推送。 */
    private final Map<String, Long> processedMessageIds = new ConcurrentHashMap<>();
    private static final long MESSAGE_DEDUP_TTL_MILLIS = 10 * 60 * 1000L;

    @Override
    @Async
    public void process(String payload) {
        try {
            log.info("Webhook payload received: {}", abbreviate(payload));
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
                        log.info("Webhook change ignored: value/messages is null, field={}", change.getField());
                        return;
                    }
                    log.info("Webhook change parsed. field={}, messageCount={}, contactCount={}",
                            change.getField(),
                            change.getValue().getMessages().size(),
                            change.getValue().getContacts() == null ? 0 : change.getValue().getContacts().size());

                    String businessAccountId = "1019964791197772";
                    if (change.getValue().getMetadata() != null
                            && change.getValue().getMetadata().getPhone_number_id() != null
                            && !change.getValue().getMetadata().getPhone_number_id().isBlank()) {
                        businessAccountId = change.getValue().getMetadata().getPhone_number_id();
                    }

                    String finalBusinessAccountId = businessAccountId;
                    change.getValue().getMessages().forEach(message -> {
                        String contactName = resolveContactName(change.getValue(), message == null ? null : message.getFrom());
                        processOneMessage(finalBusinessAccountId, message, contactName);
                    });
                });
            });
        } catch (Exception e) {
            log.error("Webhook processing error. payload={}", abbreviate(payload), e);
        }
    }

    private void processOneMessage(String businessAccountId, com.example.aitmk.model.webhook.Message message, String contactName) {
        try {
            WhatsAppMessage parsed = WhatsAppMessageParser.parse(message);
            if (isDuplicateWebhook(message)) {
                return;
            }
            String rawCustomerPhone = parsed.getFrom();
            String customerPhone = normalizeCustomerPhone(rawCustomerPhone);
            String customerContent = buildCustomerContent(parsed);

            if (!StringUtils.hasText(customerPhone)) {
                log.warn("Skip message because customer phone is blank. rawPhone={}", rawCustomerPhone);
                return;
            }

            Instant lastCustomerBefore = chatHistoryService.lastCustomerMessageTime(customerPhone).orElse(null);

            log.info("Webhook message received. rawPhone={}, normalizedPhone={}, type={}, hasText={}, lastCustomerBefore={}",
                    rawCustomerPhone, customerPhone, parsed.getType(), StringUtils.hasText(parsed.getText()), lastCustomerBefore);

            String assignedAgent = lookupAssignedAgent(rawCustomerPhone, customerPhone);
            boolean simulatedWebhook = isSimulatedWebhook(message);
            long hoursFromLastCustomerMessage = lastCustomerBefore == null
                    ? -1L
                    : Duration.between(lastCustomerBefore, Instant.now()).toHours();

            if (StringUtils.hasText(assignedAgent) && hoursFromLastCustomerMessage > 24L * 30L) {
                try {
                    crmOpenApiService.closeServingAssignment(customerPhone);
                } catch (Exception ex) {
                    log.warn("Close assignment by 30-day rule failed. customer={}", customerPhone, ex);
                }
                agentDispatchService.unassignCustomer(customerPhone);
                assignedAgent = null;
                log.info("Assignment closed by 30-day rule, process as new session. customer={}", customerPhone);
            } else if (StringUtils.hasText(assignedAgent) && hoursFromLastCustomerMessage > 24) {
                try {
                    crmOpenApiService.updateServingAssignmentReplyable(customerPhone, false);
                } catch (Exception ex) {
                    log.warn("Update replyable=false by 24-hour rule failed. customer={}", customerPhone, ex);
                }
            } else if (StringUtils.hasText(assignedAgent)) {
                try {
                    crmOpenApiService.updateServingAssignmentReplyable(customerPhone, true);
                } catch (Exception ex) {
                    log.warn("Update replyable=true failed. customer={}", customerPhone, ex);
                }
            }

            // 1) 本地状态先更新：保证第三方调用异常不会影响本地缓存完整性
            ChatMessageRecord.ReferralInfo referralInfo = buildReferralInfo(parsed);
            chatHistoryService.setCustomerNickname(customerPhone, contactName);
            chatHistoryService.recordCustomerMessage(customerPhone, customerContent, referralInfo);
            agentDispatchService.markCustomerMessageAt(customerPhone);
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
                    .referral(referralInfo)
                    .build();

            boolean assignedAgentOnline = StringUtils.hasText(assignedAgent)
                    && agentDispatchService.onlineAgentsSnapshot().contains(assignedAgent);

            if (StringUtils.hasText(assignedAgent) && assignedAgentOnline) {
                // 已有坐席接待且在线：停止 AI 自动回复，仅推送给坐席
                try {
                    agentPushService.pushNewMessage(assignedAgent, customerPhone, customerRecord);
                    log.info("Pushed new customer message to assigned agent. agent={}, customer={}", assignedAgent, customerPhone);
                } catch (Exception ex) {
                    log.error("Push new message to assigned agent failed. agent={}, customer={}", assignedAgent, customerPhone, ex);
                }
                return;
            }

            if (StringUtils.hasText(assignedAgent)) {
                // 已有坐席接待但离线：保持客户-坐席关系不变，走 AI 自动回复兜底
                doAiReplyFlow(businessAccountId, customerPhone, customerContent, simulatedWebhook);
                return;
            }

            // 3) 未分配客户：先保证本地队列状态正确
            boolean hasOnlineAgent = agentDispatchService.hasOnlineAgent();
            if (!hasOnlineAgent) {
                agentDispatchService.markUnassigned(customerPhone);
                log.info("Customer marked pending because no online agent. customer={}", customerPhone);
            }
            try {
                String reason = hasOnlineAgent ? "首次会话" : "非工作日";
                crmOpenApiService.openAiReception(customerPhone, reason);
            } catch (Exception ex) {
                log.warn("Open AI reception failed. customer={}", customerPhone, ex);
            }

            // 4) 未分配客户走 AI流程（失败不影响本地缓存）
            doAiReplyFlow(businessAccountId, customerPhone, customerContent, simulatedWebhook);

            // 5) 若当前有在线坐席，始终尝试本地分配（不受AI/CRM异常影响）
            if (hasOnlineAgent) {
                agentDispatchService.assignIfAbsent(customerPhone).ifPresentOrElse(agentRowId -> {
                    log.info("Customer assigned locally. customer={}, agent={}", customerPhone, agentRowId);
                    try {
                        boolean crmOk = crmOpenApiService.addAssignmentRecord(customerPhone, agentRowId, "服务中");
                        if (!crmOk) {
                            log.warn("CRM add assignment returned false. customer={}, agent={}", customerPhone, agentRowId);
                        }
                        crmOpenApiService.assignAiReception(customerPhone);
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

    private String resolveContactName(com.example.aitmk.model.webhook.WhatsAppValue value, String from) {
        if (value == null || value.getContacts() == null || value.getContacts().isEmpty()) {
            return "";
        }
        String normalizedFrom = normalizeCustomerPhone(from);
        for (com.example.aitmk.model.webhook.WhatsAppValue.Contact contact : value.getContacts()) {
            if (contact == null) {
                continue;
            }
            String waId = normalizeCustomerPhone(contact.getWa_id());
            if (StringUtils.hasText(normalizedFrom) && !normalizedFrom.equals(waId)) {
                continue;
            }
            if (contact.getProfile() != null && StringUtils.hasText(contact.getProfile().getName())) {
                return contact.getProfile().getName().trim();
            }
        }
        return "";
    }

    private ChatMessageRecord.ReferralInfo buildReferralInfo(WhatsAppMessage parsed) {
        if (parsed == null) {
            return null;
        }
        boolean hasReferral = StringUtils.hasText(parsed.getReferralSourceUrl())
                || StringUtils.hasText(parsed.getReferralSourceId())
                || StringUtils.hasText(parsed.getReferralSourceType())
                || StringUtils.hasText(parsed.getReferralBody())
                || StringUtils.hasText(parsed.getReferralHeadline())
                || StringUtils.hasText(parsed.getReferralMediaType())
                || StringUtils.hasText(parsed.getReferralImageUrl())
                || StringUtils.hasText(parsed.getReferralVideoUrl())
                || StringUtils.hasText(parsed.getReferralThumbnailUrl())
                || StringUtils.hasText(parsed.getReferralCtaClid())
                || StringUtils.hasText(parsed.getReferralWelcomeText());
        if (!hasReferral) {
            return null;
        }
        return ChatMessageRecord.ReferralInfo.builder()
                .sourceUrl(parsed.getReferralSourceUrl())
                .sourceId(parsed.getReferralSourceId())
                .sourceType(parsed.getReferralSourceType())
                .body(parsed.getReferralBody())
                .headline(parsed.getReferralHeadline())
                .mediaType(parsed.getReferralMediaType())
                .imageUrl(parsed.getReferralImageUrl())
                .videoUrl(parsed.getReferralVideoUrl())
                .thumbnailUrl(parsed.getReferralThumbnailUrl())
                .ctwaClid(parsed.getReferralCtaClid())
                .welcomeText(parsed.getReferralWelcomeText())
                .build();
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

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ");
        return normalized.length() > 800 ? normalized.substring(0, 800) + "...(truncated)" : normalized;
    }

    private boolean isSimulatedWebhook(com.example.aitmk.model.webhook.Message message) {
        if (message == null || !StringUtils.hasText(message.getId())) {
            return false;
        }
        return message.getId().startsWith("wamid.manual.");
    }

    private boolean isDuplicateWebhook(com.example.aitmk.model.webhook.Message message) {
        if (message == null || !StringUtils.hasText(message.getId())) {
            return false;
        }
        long now = System.currentTimeMillis();
        cleanupProcessedMessageIds(now);
        Long previous = processedMessageIds.putIfAbsent(message.getId(), now);
        if (previous == null) {
            return false;
        }
        if (now - previous <= MESSAGE_DEDUP_TTL_MILLIS) {
            log.info("Skip duplicate webhook message. messageId={}", message.getId());
            return true;
        }
        processedMessageIds.put(message.getId(), now);
        return false;
    }

    private void cleanupProcessedMessageIds(long nowMillis) {
        if (processedMessageIds.size() < 5000) {
            return;
        }
        processedMessageIds.entrySet().removeIf(e -> nowMillis - e.getValue() > MESSAGE_DEDUP_TTL_MILLIS);
    }

    private void doAiReplyFlow(String businessAccountId,
                               String customerPhone,
                               String customerContent,
                               boolean simulatedWebhook) {
        try {
            String aiReplyJson = aiService.chat(customerContent);
            String aiAnswer = AiReplyParser.parseAnswer(aiReplyJson);

            chatHistoryService.recordAiReply(customerPhone, aiAnswer);
            log.info("Local history recorded for AI reply. customer={}", customerPhone);

            if (simulatedWebhook) {
                log.info("Skip Meta send for simulated webhook message. customer={}", customerPhone);
            } else {
                sendService.sendTextMessage(businessAccountId, customerPhone, aiAnswer);
            }

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
    }
}
