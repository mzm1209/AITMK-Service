package com.example.aitmk.tools;

import com.example.aitmk.model.entity.AssignmentRecordEntity;
import com.example.aitmk.model.entity.ChatMessageEntity;
import com.example.aitmk.model.entity.ConversationAgentStateEntity;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.PersistenceEnums.AssignType;
import com.example.aitmk.model.entity.PersistenceEnums.AssignmentStatus;
import com.example.aitmk.model.entity.PersistenceEnums.ConversationStatus;
import com.example.aitmk.model.entity.PersistenceEnums.MessageType;
import com.example.aitmk.model.entity.PersistenceEnums.ResourceStatus;
import com.example.aitmk.model.entity.PersistenceEnums.SenderType;
import com.example.aitmk.model.entity.PersistenceEnums.SentStatus;
import com.example.aitmk.model.entity.PersistenceEnums.SourceChannel;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.AssignmentRecordRepository;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.repository.ConversationAgentStateRepository;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.BusinessResourceService;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrmHistoryMigrationService {
    private static final String ASSIGNMENT_WORKSHEET_ID = "ltjl";
    private static final String ASSIGN_CUSTOMER_PHONE_CONTROL_ID = "69abb3a0433ec9f4b5e6cff4";
    private static final String ASSIGN_AGENT_CONTROL_ID = "69abbcaf433ec9f4b5e6d0f6";
    private static final String ASSIGN_TIME_CONTROL_ID = "69abb8d7433ec9f4b5e6d05f";
    private static final String ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID = "69abb984433ec9f4b5e6d069";
    private static final String ASSIGN_SERVICE_STATUS_CONTROL_ID = "69abba17433ec9f4b5e6d06e";
    private static final String ASSIGN_REPLYABLE_CONTROL_ID = "69d4b066433ec9f4b5e86d1d";

    private static final String CHAT_WORKSHEET_ID = "ltjl1";
    private static final String CHAT_BUSINESS_ACCOUNT_CONTROL_ID = "69abbccf433ec9f4b5e6d0fe";
    private static final String CHAT_CUSTOMER_PHONE_CONTROL_ID = "69abbd3b433ec9f4b5e6d108";
    private static final String CHAT_AGENT_CONTROL_ID = "69abbd3b433ec9f4b5e6d109";
    private static final String CHAT_SENDER_CONTROL_ID = "69abbfff433ec9f4b5e6d226";
    private static final String CHAT_SEND_TIME_CONTROL_ID = "69abbfff433ec9f4b5e6d227";
    private static final String CHAT_CONTENT_CONTROL_ID = "69abbfff433ec9f4b5e6d228";

    private static final DateTimeFormatter CRM_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");

    private final CrmOpenApiService crm;
    private final BusinessResourceService businessResources;
    private final ResourceRepository resourceRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AssignmentRecordRepository assignmentRepository;
    private final ConversationAgentStateRepository stateRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    public CrmHistoryMigrationReport migrate(CrmHistoryMigrationOptions options) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CrmHistoryMigrationReport report = new CrmHistoryMigrationReport();
        log.info("""
                [CRM-MIGRATION] started
                  dryRun={}
                  pageSize={}
                  startPage={}
                  maxPages={}
                  customerPhone={}
                  migrateAssignments={}
                  migrateMessages={}
                  initializeUnread={}
                  stopOnError={}
                """, options.dryRun(), options.pageSize(), options.startPage(), options.maxPages(),
                value(options.customerPhone()), options.migrateAssignments(), options.migrateMessages(),
                options.initializeUnread(), options.stopOnError());

        Map<String, CustomerBundle> customers = new LinkedHashMap<>();
        if (options.migrateAssignments()) {
            scanAssignments(options, customers, report);
        }
        if (options.migrateMessages()) {
            scanMessages(options, customers, report);
        }
        report.uniqueCustomers = customers.size();
        log.info("[CRM-MIGRATION] crm data summary customers={} assignmentRowsSeen={} messageRowsSeen={}",
                customers.size(), report.assignmentRowsSeen, report.messageRowsSeen);

        int processed = 0;
        for (CustomerBundle bundle : customers.values()) {
            processed++;
            try {
                if (options.dryRun()) {
                    simulateCustomer(bundle, report);
                } else {
                    tx.executeWithoutResult(ignored -> migrateCustomer(bundle, options, report));
                }
            } catch (Exception ex) {
                report.failedCustomers++;
                report.fail("CUSTOMER_MIGRATION_FAILED");
                log.warn("[CRM-MIGRATION] customer failed phone={} errorType={} message={}",
                        bundle.customerPhone, ex.getClass().getSimpleName(), ex.getMessage(), ex);
                if (options.stopOnError()) {
                    throw ex;
                }
            }
            if (processed % options.logEvery() == 0 || processed == customers.size()) {
                log.info("[CRM-MIGRATION] progress customers={}/{} resourcesCreated={} conversationsCreated={} assignmentsInserted={} messagesInserted={} duplicates={} failedRows={}",
                        processed, customers.size(), report.resourcesCreated, report.conversationsCreated,
                        report.assignmentsInserted, report.messagesInserted, report.messagesSkippedDuplicate,
                        report.failedRows);
            }
        }
        return report;
    }

    private void scanAssignments(CrmHistoryMigrationOptions options, Map<String, CustomerBundle> customers,
                                 CrmHistoryMigrationReport report) {
        log.info("[CRM-MIGRATION] scanning CRM assignments worksheet={}", ASSIGNMENT_WORKSHEET_ID);
        int page = options.startPage();
        int pagesRead = 0;
        while (true) {
            JsonNode root = crm.frontendGetFilterRows(ASSIGNMENT_WORKSHEET_ID, filters(options.customerPhone(),
                    ASSIGN_CUSTOMER_PHONE_CONTROL_ID), options.pageSize(), page, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) {
                report.fail("CRM_ASSIGNMENT_REQUEST_FAILED");
                log.warn("[CRM-MIGRATION] assignment page request failed page={}", page);
                break;
            }
            JsonNode rows = root.path("data").path("rows");
            if (!rows.isArray() || rows.isEmpty()) break;
            log.info("[CRM-MIGRATION] assignments page={} rows={} totalSeen={}",
                    page, rows.size(), report.assignmentRowsSeen + rows.size());
            for (JsonNode row : rows) {
                report.assignmentRowsSeen++;
                parseAssignment(row, options, report).ifPresent(record ->
                        customers.computeIfAbsent(record.customerPhone(), CustomerBundle::new).assignments.add(record));
            }
            pagesRead++;
            if (rows.size() < options.pageSize()) break;
            if (options.maxPages() > 0 && pagesRead >= options.maxPages()) break;
            page++;
        }
        log.info("[CRM-MIGRATION] assignment summary rowsSeen={} valid={} invalid={} serving={} closed={} replyableYes={} replyableNo={}",
                report.assignmentRowsSeen, report.assignmentRowsValid, report.assignmentRowsInvalid,
                report.assignmentServingRows, report.assignmentClosedRows, report.assignmentReplyableYes,
                report.assignmentReplyableNo);
    }

    private void scanMessages(CrmHistoryMigrationOptions options, Map<String, CustomerBundle> customers,
                              CrmHistoryMigrationReport report) {
        log.info("[CRM-MIGRATION] scanning CRM messages worksheet={}", CHAT_WORKSHEET_ID);
        int page = options.startPage();
        int pagesRead = 0;
        while (true) {
            JsonNode root = crm.frontendGetFilterRows(CHAT_WORKSHEET_ID, filters(options.customerPhone(),
                    CHAT_CUSTOMER_PHONE_CONTROL_ID), options.pageSize(), page, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) {
                report.fail("CRM_MESSAGE_REQUEST_FAILED");
                log.warn("[CRM-MIGRATION] message page request failed page={}", page);
                break;
            }
            JsonNode rows = root.path("data").path("rows");
            if (!rows.isArray() || rows.isEmpty()) break;
            log.info("[CRM-MIGRATION] messages page={} rows={} totalSeen={}",
                    page, rows.size(), report.messageRowsSeen + rows.size());
            for (JsonNode row : rows) {
                report.messageRowsSeen++;
                parseMessage(row, options, report).ifPresent(message ->
                        customers.computeIfAbsent(message.customerPhone(), CustomerBundle::new).messages.add(message));
            }
            pagesRead++;
            if (rows.size() < options.pageSize()) break;
            if (options.maxPages() > 0 && pagesRead >= options.maxPages()) break;
            page++;
        }
        log.info("[CRM-MIGRATION] message summary rowsSeen={} valid={} invalid={} customer={} agent={} ai={} system={}",
                report.messageRowsSeen, report.messageRowsValid, report.messageRowsInvalid, report.senderCustomer,
                report.senderAgent, report.senderAi, report.senderSystem);
    }

    private Optional<CrmAssignment> parseAssignment(JsonNode row, CrmHistoryMigrationOptions options,
                                                   CrmHistoryMigrationReport report) {
        String phone = text(row, ASSIGN_CUSTOMER_PHONE_CONTROL_ID);
        if (!StringUtils.hasText(phone)) {
            report.invalidAssignment("ASSIGNMENT_BLANK_PHONE");
            return Optional.empty();
        }
        Instant assignedAt = parseTime(text(row, ASSIGN_TIME_CONTROL_ID));
        if (assignedAt == null) {
            report.invalidAssignment("ASSIGNMENT_INVALID_TIME");
            return Optional.empty();
        }
        if (!inRange(assignedAt, options)) return Optional.empty();
        String agent = text(row, ASSIGN_AGENT_CONTROL_ID);
        String status = text(row, ASSIGN_SERVICE_STATUS_CONTROL_ID);
        boolean serving = "服务中".equals(status);
        if (!StringUtils.hasText(agent) && serving) {
            report.invalidAssignment("ASSIGNMENT_BLANK_AGENT");
            return Optional.empty();
        }
        boolean replyable = !"否".equals(text(row, ASSIGN_REPLYABLE_CONTROL_ID));
        Instant lastCallAt = parseTime(text(row, ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID));
        report.assignmentRowsValid++;
        if (serving) report.assignmentServingRows++;
        else report.assignmentClosedRows++;
        if (replyable) report.assignmentReplyableYes++;
        else report.assignmentReplyableNo++;
        return Optional.of(new CrmAssignment(row.path("rowid").asText(""), phone.trim(), normalizeRelation(agent),
                status, serving, replyable, assignedAt, lastCallAt, raw(row)));
    }

    private Optional<CrmMessage> parseMessage(JsonNode row, CrmHistoryMigrationOptions options,
                                              CrmHistoryMigrationReport report) {
        String phone = text(row, CHAT_CUSTOMER_PHONE_CONTROL_ID);
        if (!StringUtils.hasText(phone)) {
            report.invalidMessage("MESSAGE_BLANK_PHONE");
            return Optional.empty();
        }
        Instant sentAt = parseTime(text(row, CHAT_SEND_TIME_CONTROL_ID));
        if (sentAt == null) {
            report.invalidMessage("MESSAGE_INVALID_TIME");
            return Optional.empty();
        }
        if (!inRange(sentAt, options)) return Optional.empty();
        String content = text(row, CHAT_CONTENT_CONTROL_ID);
        if (!StringUtils.hasText(content)) {
            report.invalidMessage("MESSAGE_BLANK_CONTENT");
            return Optional.empty();
        }
        SenderType sender = sender(text(row, CHAT_SENDER_CONTROL_ID));
        switch (sender) {
            case CUSTOMER -> report.senderCustomer++;
            case AGENT, MANAGER -> report.senderAgent++;
            case AI -> report.senderAi++;
            default -> report.senderSystem++;
        }
        report.messageRowsValid++;
        String rowId = row.path("rowid").asText("");
        String externalId = StringUtils.hasText(rowId)
                ? "crm:ltjl1:" + rowId.trim()
                : fallbackMessageId(phone, sentAt, sender, content);
        return Optional.of(new CrmMessage(rowId, externalId, phone.trim(), text(row, CHAT_BUSINESS_ACCOUNT_CONTROL_ID),
                normalizeRelation(text(row, CHAT_AGENT_CONTROL_ID)), sender, content, sentAt, raw(row)));
    }

    private void simulateCustomer(CustomerBundle bundle, CrmHistoryMigrationReport report) {
        boolean resourceExists = resourceRepository.findByCustomerPhone(bundle.customerPhone).isPresent();
        if (resourceExists) report.resourcesReused++;
        else report.resourcesCreated++;
        report.conversationsCreated++;
        if (bundle.selectedAssignment() != null) report.assignmentsInserted++;
        report.messagesInserted += bundle.messages.stream()
                .filter(m -> !messageRepository.existsByExternalMessageId(m.externalMessageId()))
                .count();
        report.messagesSkippedDuplicate += bundle.messages.stream()
                .filter(m -> messageRepository.existsByExternalMessageId(m.externalMessageId()))
                .count();
    }

    private void migrateCustomer(CustomerBundle bundle, CrmHistoryMigrationOptions options,
                                 CrmHistoryMigrationReport report) {
        bundle.messages.sort(Comparator.comparing(CrmMessage::sentAt));
        bundle.assignments.sort(Comparator.comparing(CrmAssignment::assignedAt));
        CrmAssignment selected = bundle.selectedAssignment();
        if ((selected == null || !StringUtils.hasText(selected.agentRowId()))) {
            selected = bundle.assignmentFromLatestMessageAgent(selected);
        }
        boolean serving = selected != null && selected.serving();
        TimeFacts times = TimeFacts.from(bundle, selected);

        boolean resourceExisted = resourceRepository.findByCustomerPhone(bundle.customerPhone).isPresent();
        ResourceEntity resource = businessResources.getOrCreateByPhone(bundle.customerPhone);
        if (resourceExisted) report.resourcesReused++;
        else report.resourcesCreated++;
        applyResource(resource, selected, serving, times);
        resourceRepository.saveAndFlush(resource);

        ConversationEntity conversation = findOrCreateConversation(resource, selected, serving, times, report);
        migrateAssignment(resource, conversation, selected, serving, options, report);
        for (CrmMessage message : bundle.messages) {
            migrateMessage(resource, conversation, message, report);
        }
        applyConversation(conversation, selected, serving, times);
        conversationRepository.saveAndFlush(conversation);
        if (options.initializeUnread() && serving && selected != null && StringUtils.hasText(selected.agentRowId())) {
            initializeUnread(conversation, selected.agentRowId(), report);
        }
        log.debug("[CRM-MIGRATION] customer migrated phone={} messages={} assignment={} agent={} resourceId={} conversationId={}",
                bundle.customerPhone, bundle.messages.size(), selected == null ? "NONE" : selected.status(),
                selected == null ? "" : selected.agentRowId(), resource.getId(), conversation.getId());
    }

    private void applyResource(ResourceEntity resource, CrmAssignment assignment, boolean serving, TimeFacts times) {
        resource.setSourceChannel(SourceChannel.CRM);
        if (!StringUtils.hasText(resource.getSourceExternalId())) resource.setSourceExternalId(resource.getCustomerPhone());
        resource.setResourceStatus(serving ? ResourceStatus.ASSIGNED : ResourceStatus.CLOSED);
        resource.setAssignedAgentId(serving && assignment != null ? assignment.agentRowId() : null);
        resource.setAssignedAt(serving && assignment != null ? assignment.assignedAt() : null);
        resource.setLastMessageAt(times.lastMessageAt());
        resource.setLastCustomerMessageAt(times.lastCustomerMessageAt());
        resource.setLastAgentMessageAt(times.lastAgentMessageAt());
        if (times.createdAt() != null && resource.getCreatedAt() == null) {
            resource.setCreatedAt(times.createdAt());
        }
    }

    private ConversationEntity findOrCreateConversation(ResourceEntity resource, CrmAssignment assignment,
                                                        boolean serving, TimeFacts times,
                                                        CrmHistoryMigrationReport report) {
        ConversationEntity conversation = conversationRepository.findFirstByResourceIdOrderByCreatedAtDescIdDesc(resource.getId())
                .orElse(null);
        if (conversation != null) {
            report.conversationsReused++;
        } else {
            conversation = new ConversationEntity();
            conversation.setResourceId(resource.getId());
            conversation.setCustomerPhone(resource.getCustomerPhone());
            conversation.setCreatedAt(times.createdAt() == null ? Instant.now() : times.createdAt());
            report.conversationsCreated++;
        }
        applyConversation(conversation, assignment, serving, times);
        return conversationRepository.saveAndFlush(conversation);
    }

    private void applyConversation(ConversationEntity conversation, CrmAssignment assignment, boolean serving,
                                   TimeFacts times) {
        conversation.setChannel(SourceChannel.CRM);
        conversation.setStatus(serving ? ConversationStatus.HUMAN_ACTIVE : ConversationStatus.CLOSED);
        conversation.setAssignedAgentId(assignment == null ? null : assignment.agentRowId());
        conversation.setBusinessAccountId(times.businessAccountId());
        conversation.setFirstCustomerMessageAt(times.firstCustomerMessageAt());
        conversation.setFirstAgentReplyAt(times.firstAgentReplyAt());
        conversation.setFirstAiReplyAt(times.firstAiReplyAt());
        conversation.setLastMessageAt(times.lastMessageAt());
        if (!serving) {
            conversation.setClosedAt(times.closedAt() == null ? Instant.now() : times.closedAt());
            conversation.setClosedBy("CRM_MIGRATION");
            conversation.setCloseReason("CRM_MIGRATION_HISTORY_CLOSED");
        } else {
            conversation.setClosedAt(null);
            conversation.setClosedBy(null);
            conversation.setCloseReason(null);
        }
    }

    private void migrateAssignment(ResourceEntity resource, ConversationEntity conversation, CrmAssignment selected,
                                   boolean serving, CrmHistoryMigrationOptions options,
                                   CrmHistoryMigrationReport report) {
        if (selected == null || !StringUtils.hasText(selected.agentRowId())) return;
        if (serving) {
            Optional<AssignmentRecordEntity> existing = assignmentRepository
                    .findFirstByResourceIdAndStatusOrderByAssignedAtDesc(resource.getId(), AssignmentStatus.SERVING);
            if (existing.isPresent()) {
                if (selected.agentRowId().equals(existing.get().getAgentId())) {
                    report.assignmentsSkippedDuplicate++;
                    return;
                }
                if (!options.replaceServingAssignment()) {
                    report.assignmentsConflict++;
                    log.warn("[CRM-MIGRATION] serving assignment conflict phone={} existingAgent={} crmAgent={}",
                            resource.getCustomerPhone(), existing.get().getAgentId(), selected.agentRowId());
                    return;
                }
                AssignmentRecordEntity old = existing.get();
                old.setStatus(AssignmentStatus.CLOSED);
                old.setReplyable(false);
                old.setClosedAt(Instant.now());
                old.setCloseReason("CRM_MIGRATION_REPLACED");
                assignmentRepository.saveAndFlush(old);
            }
        } else if (assignmentRepository.findByConversationIdOrderByAssignedAtDesc(conversation.getId()).stream()
                .anyMatch(a -> selected.agentRowId().equals(a.getAgentId()) && a.getStatus() == AssignmentStatus.CLOSED)) {
            report.assignmentsSkippedDuplicate++;
            return;
        }

        AssignmentRecordEntity record = new AssignmentRecordEntity();
        record.setResourceId(resource.getId());
        record.setConversationId(conversation.getId());
        record.setCustomerPhone(resource.getCustomerPhone());
        record.setAgentId(selected.agentRowId());
        record.setAssignedBy("CRM_MIGRATION");
        record.setAssignType(AssignType.MANUAL);
        record.setStatus(serving ? AssignmentStatus.SERVING : AssignmentStatus.CLOSED);
        record.setReplyable(serving && selected.replyable());
        record.setAssignedAt(selected.assignedAt());
        if (!serving) {
            record.setClosedAt(selected.lastCallAt() == null ? Instant.now() : selected.lastCallAt());
            record.setCloseReason("CRM_MIGRATION_HISTORY_CLOSED");
        }
        try {
            assignmentRepository.saveAndFlush(record);
            report.assignmentsInserted++;
        } catch (DataIntegrityViolationException ex) {
            report.assignmentsConflict++;
            log.warn("[CRM-MIGRATION] assignment insert conflict phone={} agent={} status={}",
                    resource.getCustomerPhone(), selected.agentRowId(), record.getStatus());
        }
    }

    private void migrateMessage(ResourceEntity resource, ConversationEntity conversation, CrmMessage crmMessage,
                                CrmHistoryMigrationReport report) {
        if (messageRepository.existsByExternalMessageId(crmMessage.externalMessageId())) {
            report.messagesSkippedDuplicate++;
            return;
        }
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(conversation.getId());
        message.setResourceId(resource.getId());
        message.setCustomerPhone(resource.getCustomerPhone());
        message.setBusinessAccountId(crmMessage.businessAccountId());
        message.setChannel(SourceChannel.CRM);
        message.setExternalMessageId(crmMessage.externalMessageId());
        message.setSenderType(crmMessage.senderType());
        message.setSenderId(StringUtils.hasText(crmMessage.agentRowId()) ? crmMessage.agentRowId() : null);
        message.setMessageType(MessageType.TEXT);
        message.setContent(crmMessage.content());
        message.setRawPayload(crmMessage.rawJson());
        message.setSentStatus(crmMessage.senderType() == SenderType.CUSTOMER ? SentStatus.DELIVERED : SentStatus.SENT);
        if (crmMessage.senderType() == SenderType.CUSTOMER) message.setDeliveredAt(crmMessage.sentAt());
        else message.setSentAt(crmMessage.sentAt());
        message.setCreatedAt(crmMessage.sentAt());
        try {
            messageRepository.saveAndFlush(message);
            report.messagesInserted++;
        } catch (DataIntegrityViolationException ex) {
            report.messagesSkippedDuplicate++;
        }
    }

    private void initializeUnread(ConversationEntity conversation, String agentId, CrmHistoryMigrationReport report) {
        if (stateRepository.findByConversationIdAndAgentId(conversation.getId(), agentId).isPresent()) return;
        ConversationAgentStateEntity state = new ConversationAgentStateEntity();
        state.setConversationId(conversation.getId());
        state.setAgentId(agentId);
        state.setUnreadCount(messageRepository.countByConversationIdAndSenderType(
                conversation.getId(), SenderType.CUSTOMER));
        stateRepository.saveAndFlush(state);
        report.unreadStatesCreated++;
    }

    private List<Map<String, Object>> filters(String customerPhone, String phoneControlId) {
        if (!StringUtils.hasText(customerPhone)) return List.of();
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", phoneControlId);
        item.put("dataType", 2);
        item.put("spliceType", 1);
        item.put("filterType", 2);
        item.put("value", customerPhone.trim());
        return List.of(item);
    }

    private boolean inRange(Instant instant, CrmHistoryMigrationOptions options) {
        if (instant == null) return false;
        if (options.from() != null && instant.isBefore(options.from())) return false;
        return options.to() == null || !instant.isAfter(options.to());
    }

    private String text(JsonNode row, String field) {
        if (row == null || field == null) return "";
        JsonNode node = row.get(field);
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) {
            String value = node.asText("");
            String relation = normalizeRelation(value);
            return StringUtils.hasText(relation) ? relation : value.trim();
        }
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            for (String name : List.of("sid", "id", "rowid", "name", "text", "value")) {
                if (first.has(name)) return first.path(name).asText("");
            }
            return first.toString();
        }
        if (node.isObject()) {
            for (String name : List.of("sid", "id", "rowid", "name", "text", "value")) {
                if (node.has(name)) return node.path(name).asText("");
            }
        }
        return node.asText(node.toString()).trim();
    }

    private String normalizeRelation(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String text = raw.trim();
        if (!(text.startsWith("{") || text.startsWith("["))) return text;
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node.isArray() && !node.isEmpty()) return relationNode(node.get(0));
            if (node.isObject()) return relationNode(node);
        } catch (Exception ignored) {
            return text;
        }
        return text;
    }

    private String relationNode(JsonNode node) {
        for (String name : List.of("sid", "id", "rowid", "accountId")) {
            if (node.has(name)) return node.path(name).asText("");
        }
        return node.asText("");
    }

    private Instant parseTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim();
        try {
            return LocalDateTime.parse(text, CRM_TIME_FORMAT).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private SenderType sender(String raw) {
        if (!StringUtils.hasText(raw)) return SenderType.SYSTEM;
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.contains("客户") || text.equals("customer") || text.equals("user")) return SenderType.CUSTOMER;
        if (text.contains("ai") || text.contains("机器人")) return SenderType.AI;
        if (text.contains("人工") || text.contains("客服") || text.contains("坐席") || text.equals("agent")) {
            return SenderType.AGENT;
        }
        return SenderType.SYSTEM;
    }

    private String fallbackMessageId(String phone, Instant sentAt, SenderType sender, String content) {
        String source = phone + "|" + sentAt + "|" + sender + "|" + content;
        return "crm:ltjl1:fallback:" + DigestUtils.md5DigestAsHex(source.getBytes(StandardCharsets.UTF_8));
    }

    private String raw(JsonNode row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (Exception ignored) {
            return row == null ? "" : row.toString();
        }
    }

    private String value(String text) {
        return text == null ? "" : text;
    }

    private record CrmAssignment(String crmRowId, String customerPhone, String agentRowId, String status,
                                 boolean serving, boolean replyable, Instant assignedAt, Instant lastCallAt,
                                 String rawJson) {}

    private record CrmMessage(String crmRowId, String externalMessageId, String customerPhone,
                              String businessAccountId, String agentRowId, SenderType senderType,
                              String content, Instant sentAt, String rawJson) {}

    private static class CustomerBundle {
        private final String customerPhone;
        private final List<CrmAssignment> assignments = new ArrayList<>();
        private final List<CrmMessage> messages = new ArrayList<>();

        private CustomerBundle(String customerPhone) {
            this.customerPhone = customerPhone;
        }

        private CrmAssignment selectedAssignment() {
            return assignments.stream()
                    .filter(assignment -> assignment.serving() && StringUtils.hasText(assignment.agentRowId()))
                    .max(Comparator.comparing(CrmAssignment::assignedAt))
                    .orElseGet(() -> assignments.stream()
                            .filter(assignment -> StringUtils.hasText(assignment.agentRowId()))
                            .max(Comparator.comparing(CrmAssignment::assignedAt))
                            .orElseGet(() -> assignments.stream()
                                    .max(Comparator.comparing(CrmAssignment::assignedAt))
                                    .orElse(null)));
        }

        private CrmAssignment assignmentFromLatestMessageAgent(CrmAssignment selected) {
            return messages.stream()
                    .filter(message -> StringUtils.hasText(message.agentRowId()))
                    .max(Comparator.comparing(CrmMessage::sentAt))
                    .map(message -> new CrmAssignment(
                            selected == null ? "" : selected.crmRowId(),
                            customerPhone,
                            message.agentRowId(),
                            selected == null ? "历史关闭" : selected.status(),
                            false,
                            false,
                            selected == null ? message.sentAt() : selected.assignedAt(),
                            selected == null ? message.sentAt() : selected.lastCallAt(),
                            selected == null ? "" : selected.rawJson()))
                    .orElse(selected);
        }
    }

    private record TimeFacts(Instant createdAt, Instant firstCustomerMessageAt, Instant firstAgentReplyAt,
                             Instant firstAiReplyAt, Instant lastMessageAt, Instant lastCustomerMessageAt,
                             Instant lastAgentMessageAt, Instant closedAt, String businessAccountId) {
        private static TimeFacts from(CustomerBundle bundle, CrmAssignment assignment) {
            Instant created = null;
            Instant firstCustomer = null;
            Instant firstAgent = null;
            Instant firstAi = null;
            Instant last = null;
            Instant lastCustomer = null;
            Instant lastAgent = null;
            String account = null;
            for (CrmMessage message : bundle.messages) {
                Instant at = message.sentAt();
                if (created == null || at.isBefore(created)) created = at;
                if (last == null || at.isAfter(last)) last = at;
                if (StringUtils.hasText(message.businessAccountId())) account = message.businessAccountId();
                if (message.senderType() == SenderType.CUSTOMER) {
                    if (firstCustomer == null || at.isBefore(firstCustomer)) firstCustomer = at;
                    if (lastCustomer == null || at.isAfter(lastCustomer)) lastCustomer = at;
                } else if (message.senderType() == SenderType.AI) {
                    if (firstAi == null || at.isBefore(firstAi)) firstAi = at;
                    if (lastAgent == null || at.isAfter(lastAgent)) lastAgent = at;
                } else if (message.senderType() == SenderType.AGENT || message.senderType() == SenderType.MANAGER) {
                    if (firstAgent == null || at.isBefore(firstAgent)) firstAgent = at;
                    if (lastAgent == null || at.isAfter(lastAgent)) lastAgent = at;
                }
            }
            if (created == null && assignment != null) created = assignment.assignedAt();
            Instant closed = assignment == null ? last : (assignment.lastCallAt() == null ? last : assignment.lastCallAt());
            return new TimeFacts(created, firstCustomer, firstAgent, firstAi, last, lastCustomer, lastAgent,
                    closed, account);
        }
    }
}
