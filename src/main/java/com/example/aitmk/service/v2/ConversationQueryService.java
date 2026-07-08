package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.AgentAccountCacheService;
import com.example.aitmk.service.WorksheetFieldService;
import com.example.aitmk.security.auth.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Objects;

@Slf4j @Service @RequiredArgsConstructor
public class ConversationQueryService {
    private static final String CLUE_WORKSHEET_ID = "leads_bank";
    private static final String LEAD_TYPE_CONTROL_ID = "681c86c01e19a610d7200418";
    private static final String LEAD_STATUS_CONTROL_ID = "66b5e34a7e23d13674f24129";

    private final ConversationRepository conversations;
    private final ResourceRepository resources;
    private final ChatMessageRepository messages;
    private final ConversationAgentStateRepository states;
    private final V2AccessService access;
    private final AgentAccountCacheService agentAccounts;
    private final LeadRecordRepository leadRecords;
    private final WorksheetFieldService worksheetFields;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public CursorPage<ConversationSummary> list(AuthenticatedUser user, String scope, String status,
            String keyword, String source, String resourceType, String resourceStatus, String queue,
            String assigned, String cursor, int requested) {
        return list(user, scope, status, keyword, source, resourceType, resourceStatus, queue,
                assigned, null, cursor, requested);
    }

    @Transactional(readOnly = true)
    public CursorPage<ConversationSummary> list(AuthenticatedUser user, String scope, String status,
            String keyword, String source, String resourceType, String resourceStatus, String queue,
            String assigned, String replyWindow, String cursor, int requested) {
        return list(user, scope, status, keyword, source, resourceType, resourceStatus, queue,
                assigned, replyWindow, null, null, cursor, requested);
    }

    @Transactional(readOnly = true)
    public CursorPage<ConversationSummary> list(AuthenticatedUser user, String scope, String status,
            String keyword, String source, String resourceType, String resourceStatus, String queue,
            String assigned, String replyWindow, String leadType, String leadStatus, String cursor, int requested) {
        int size = Math.min(Math.max(requested, 1), 100);
        V2AccessService.DataScope dataScope = access.requireScope(user, scope);
        access.requireAgentWithinScope(user, dataScope, assigned);
        boolean hasLeadFilter = hasText(leadType) || hasText(leadStatus);
        StringBuilder sql = new StringBuilder("select c.id from conversation c join business_resource r on r.id=c.resource_id ");
        if (hasLeadFilter) sql.append("join lead_records lr on lr.customer_phone = r.customer_phone ");
        sql.append("where 1=1 ");
        Map<String,Object> params = new HashMap<>();
        boolean pendingStatusCompatibility = "PENDING_ASSIGNMENT".equalsIgnoreCase(status);
        add(sql, params, "c.status", "status", pendingStatusCompatibility ? null : status);
        add(sql, params, "r.source_channel", "source", source);
        add(sql, params, "r.resource_type", "resourceType", resourceType);
        String resolvedResourceStatus = pendingStatusCompatibility || "pending".equalsIgnoreCase(queue) ? "PENDING_ASSIGNMENT" : resourceStatus;
        add(sql, params, "r.resource_status", "resourceStatus", resolvedResourceStatus);
        addReplyWindow(sql, params, replyWindow);
        add(sql, params, "lr.leads_type", "leadType", leadType);
        add(sql, params, "lr.leads_status", "leadStatus", leadStatus);
        if (keyword != null && !keyword.isBlank()) { sql.append("and (r.customer_phone like :keyword or r.customer_name like :keyword) "); params.put("keyword", "%" + keyword + "%"); }
        List<String> scopedAgents = access.agentsForScope(user, dataScope);
        if (assigned != null && !assigned.isBlank()) {
            add(sql, params, "c.assigned_agent_id", "agent", assigned.trim());
        } else if (scopedAgents != null) {
            if (scopedAgents.isEmpty()) return new CursorPage<>(List.of(), null, false);
            if (scopedAgents.size() == 1) {
                sql.append("and c.assigned_agent_id = :scopedAgent ");
                params.put("scopedAgent", scopedAgents.get(0));
            } else {
                StringBuilder placeholders = new StringBuilder("and c.assigned_agent_id in (");
                for (int i = 0; i < scopedAgents.size(); i++) {
                    if (i > 0) placeholders.append(",");
                    String name = "scopedAgent" + i;
                    placeholders.append(":").append(name);
                    params.put(name, scopedAgents.get(i));
                }
                placeholders.append(") ");
                sql.append(placeholders);
            }
        }
        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.Key key = CursorCodec.decode(cursor);
            sql.append("and (coalesce(c.last_message_at,c.created_at)<:cursorAt or (coalesce(c.last_message_at,c.created_at)=:cursorAt and c.id<:cursorId)) ");
            params.put("cursorAt", key.at()); params.put("cursorId", key.id());
        }
        sql.append("order by coalesce(c.last_message_at,c.created_at) desc,c.id desc");
        var query = em.createNativeQuery(sql.toString()); params.forEach(query::setParameter); query.setMaxResults(size + 1);
        @SuppressWarnings("unchecked") List<Number> ids = query.getResultList();
        boolean more = ids.size() > size; if (more) ids = ids.subList(0, size);
        List<ConversationEntity> entities = ids.stream()
                .map(n -> conversations.findById(n.longValue()).orElseThrow())
                .filter(c -> access.canView(user, c))
                .toList();
        Map<String,String> agentNames = agentAccounts.getNames(
                entities.stream().map(ConversationEntity::getAssignedAgentId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        List<ConversationSummary> summaries = entities.stream()
                .map(c -> summary(c, user, agentNames.get(c.getAssignedAgentId()), unreadAgentId(c, user, dataScope), true))
                .filter(Objects::nonNull).toList();
        String next = summaries.isEmpty() ? null : encode(conversations.findById(Long.valueOf(summaries.get(summaries.size()-1).conversationId())).orElseThrow());
        return new CursorPage<>(summaries, next, more);
    }

    @Transactional(readOnly = true)
    public ConversationFilterOptions filterOptions() {
        return new ConversationFilterOptions(
                filterOptions(worksheetFilterOptions(LEAD_TYPE_CONTROL_ID), leadRecords.findDistinctLeadsTypes()),
                filterOptions(worksheetFilterOptions(LEAD_STATUS_CONTROL_ID), leadRecords.findDistinctLeadsStatuses()));
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(Long id, AuthenticatedUser user) {
        ConversationEntity c = get(id, user);
        return detail(c, user, true);
    }

    @Transactional(readOnly = true)
    public ConversationDetail transferResult(Long id, AuthenticatedUser user) {
        ConversationEntity c = conversations.findById(id).orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","会话不存在"));
        return detail(c, user, false);
    }

    private ConversationDetail detail(ConversationEntity c, AuthenticatedUser user, boolean requireView) {
        ResourceEntity resource=resources.findById(c.getResourceId()).orElseThrow();
        ChatMessageEntity lastMessage=messages.findFirstByResourceIdOrderByCreatedAtDescIdDesc(resource.getId()).orElse(null);
        String agentName = agentAccounts.getName(c.getAssignedAgentId());
        return ConversationDetail.of(summary(c, user, agentName, requireView), V2Mapper.resource(resource,lastMessage,agentName));
    }

    @Transactional(readOnly = true)
    public CursorPage<MessageView> messages(Long id, String before, int requested, AuthenticatedUser user) {
        get(id, user); int size = Math.min(Math.max(requested, 1), 100); List<ChatMessageEntity> rows;
        if (before == null || before.isBlank()) rows = messages.findByConversationIdOrderByCreatedAtDescIdDesc(id, PageRequest.of(0, size + 1));
        else { CursorCodec.Key key = CursorCodec.decode(before); rows = messages.findBefore(id, key.at(), key.id(), PageRequest.of(0, size + 1)); }
        boolean more = rows.size() > size; if (more) rows = rows.subList(0, size);
        String next = rows.isEmpty() ? null : encode(rows.get(rows.size()-1).getCreatedAt(), rows.get(rows.size()-1).getId());
        Collections.reverse(rows); return new CursorPage<>(rows.stream().map(V2Mapper::message).toList(), next, more);
    }

    public ConversationEntity get(Long id, AuthenticatedUser user) {
        ConversationEntity c = conversations.findById(id).orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND,"CONVERSATION_NOT_FOUND","会话不存在"));
        access.requireView(user, c); return c;
    }

    private ConversationSummary summary(ConversationEntity c, AuthenticatedUser user, String agentName) {
        return summary(c, user, agentName, user.getAccountRowId(), true);
    }

    private ConversationSummary summary(ConversationEntity c, AuthenticatedUser user, String agentName, boolean requireView) {
        return summary(c, user, agentName, user.getAccountRowId(), requireView);
    }

    private ConversationSummary summary(ConversationEntity c, AuthenticatedUser user, String agentName, String unreadAgentId, boolean requireView) {
        if (requireView && !access.canView(user, c)) return null;
        ResourceEntity r = resources.findById(c.getResourceId()).orElseThrow();
        ConversationAgentStateEntity state = unreadAgentId == null ? null
                : states.findByConversationIdAndAgentId(c.getId(), unreadAgentId).orElse(null);
        List<ChatMessageEntity> latest = messages.findByConversationIdOrderByCreatedAtDescIdDesc(c.getId(), PageRequest.of(0,1));
        Instant deadline = r.getLastCustomerMessageAt() == null ? null : r.getLastCustomerMessageAt().plusSeconds(86400);
        boolean replyable = c.getStatus() != PersistenceEnums.ConversationStatus.CLOSED && user.hasPermission(Permission.CHAT_REPLY_ASSIGNED)
                && access.canReply(user, c.getAssignedAgentId()) && deadline != null && !deadline.isBefore(Instant.now());
        return V2Mapper.conversation(c, r, state, latest.isEmpty() ? null : latest.get(0), replyable, agentName);
    }

    private String unreadAgentId(ConversationEntity c, AuthenticatedUser user, V2AccessService.DataScope scope) {
        if (scope == V2AccessService.DataScope.MINE) return user.getAccountRowId();
        return c.getAssignedAgentId() == null ? user.getAccountRowId() : c.getAssignedAgentId();
    }

    private void add(StringBuilder sql, Map<String,Object> params, String column, String name, String value) {
        if (value != null && !value.isBlank()) { sql.append("and ").append(column).append("=:").append(name).append(' '); params.put(name, value); }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<ConversationFilterOption> worksheetFilterOptions(String controlId) {
        try {
            WorksheetFieldsView fields = worksheetFields.getFields(CLUE_WORKSHEET_ID);
            if (fields == null || fields.fields() == null) return List.of();
            return fields.fields().stream()
                    .filter(field -> controlId.equals(field.controlId()))
                    .findFirst()
                    .map(field -> field.options() == null ? List.<ConversationFilterOption>of()
                            : field.options().stream()
                                    .map(this::option)
                                    .filter(Objects::nonNull)
                                    .toList())
                    .orElse(List.of());
        } catch (Exception ex) {
            log.warn("Failed to load CRM conversation filter options from worksheet fields, using local cache only.", ex);
            return List.of();
        }
    }

    private ConversationFilterOption option(FieldOption option) {
        if (option == null) return null;
        String value = hasText(option.value()) ? option.value().trim() : hasText(option.key()) ? option.key().trim() : null;
        if (!hasText(value)) return null;
        String label = hasText(option.value()) ? option.value().trim() : value;
        return new ConversationFilterOption(value, label);
    }

    private List<ConversationFilterOption> filterOptions(List<ConversationFilterOption> worksheetOptions, List<String> localValues) {
        Map<String, ConversationFilterOption> merged = new LinkedHashMap<>();
        for (ConversationFilterOption option : worksheetOptions) {
            if (option != null && hasText(option.value())) merged.put(option.value().trim(), option);
        }
        for (String value : localValues) {
            if (!hasText(value)) continue;
            String trimmed = value.trim();
            merged.putIfAbsent(trimmed, new ConversationFilterOption(trimmed, trimmed));
        }
        return List.copyOf(merged.values());
    }

    private void addReplyWindow(StringBuilder sql, Map<String,Object> params, String replyWindow) {
        if (replyWindow == null || replyWindow.isBlank()) return;
        Instant now = Instant.now();
        Instant expiredCutoff = now.minus(24, ChronoUnit.HOURS);
        params.put("replyExpiredCutoff", expiredCutoff);
        switch (replyWindow.trim().toLowerCase(Locale.ROOT)) {
            case "open" -> sql.append("and r.last_customer_message_at is not null and r.last_customer_message_at >= :replyExpiredCutoff ");
            case "expired" -> sql.append("and r.last_customer_message_at is not null and r.last_customer_message_at < :replyExpiredCutoff ");
            case "lt15m" -> {
                params.put("replyLtCutoff", now.minus(23, ChronoUnit.HOURS).minus(45, ChronoUnit.MINUTES));
                sql.append("and r.last_customer_message_at is not null and r.last_customer_message_at >= :replyExpiredCutoff and r.last_customer_message_at <= :replyLtCutoff ");
            }
            case "lt1h" -> {
                params.put("replyLtCutoff", now.minus(23, ChronoUnit.HOURS));
                sql.append("and r.last_customer_message_at is not null and r.last_customer_message_at >= :replyExpiredCutoff and r.last_customer_message_at <= :replyLtCutoff ");
            }
            case "lt4h" -> {
                params.put("replyLtCutoff", now.minus(20, ChronoUnit.HOURS));
                sql.append("and r.last_customer_message_at is not null and r.last_customer_message_at >= :replyExpiredCutoff and r.last_customer_message_at <= :replyLtCutoff ");
            }
            default -> throw new V2Exception(HttpStatus.BAD_REQUEST, "REPLY_WINDOW_INVALID", "replyWindow 参数无效");
        }
    }
    private static String encode(ConversationEntity c) { return CursorCodec.encode(c.getLastMessageAt() == null ? c.getCreatedAt() : c.getLastMessageAt(), c.getId()); }
    private static String encode(Instant at, Long id) { return CursorCodec.encode(at, id); }

    /** Kept as the single service-level entry point for cursor validation. */
    private CursorCodec.Key decode(String cursor) {
        return CursorCodec.decode(cursor);
    }
}
