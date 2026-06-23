package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Slf4j @Service @RequiredArgsConstructor
public class ConversationQueryService {
    private final ConversationRepository conversations;
    private final ResourceRepository resources;
    private final ChatMessageRepository messages;
    private final ConversationAgentStateRepository states;
    private final V2AccessService access;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public CursorPage<ConversationSummary> list(AuthenticatedUser user, String scope, String status,
            String keyword, String source, String resourceType, String resourceStatus, String queue,
            String assigned, String cursor, int requested) {
        int size = Math.min(Math.max(requested, 1), 100);
        V2AccessService.DataScope dataScope = access.requireScope(user, scope);
        access.requireAgentWithinScope(user, dataScope, assigned);
        StringBuilder sql = new StringBuilder("select c.id from conversation c join business_resource r on r.id=c.resource_id where 1=1 ");
        Map<String,Object> params = new HashMap<>();
        boolean pendingStatusCompatibility = "PENDING_ASSIGNMENT".equalsIgnoreCase(status);
        add(sql, params, "c.status", "status", pendingStatusCompatibility ? null : status);
        add(sql, params, "r.source_channel", "source", source);
        add(sql, params, "r.resource_type", "resourceType", resourceType);
        String resolvedResourceStatus = pendingStatusCompatibility || "pending".equalsIgnoreCase(queue) ? "PENDING_ASSIGNMENT" : resourceStatus;
        add(sql, params, "r.resource_status", "resourceStatus", resolvedResourceStatus);
        if (keyword != null && !keyword.isBlank()) { sql.append("and (r.customer_phone like :keyword or r.customer_name like :keyword) "); params.put("keyword", "%" + keyword + "%"); }
        List<String> scopedAgents = access.agentsForScope(user, dataScope);
        if (assigned != null && !assigned.isBlank()) {
            add(sql, params, "c.assigned_agent_id", "agent", assigned.trim());
        } else if (scopedAgents != null) {
            if (scopedAgents.isEmpty()) return new CursorPage<>(List.of(), null, false);
            sql.append("and c.assigned_agent_id in (:scopedAgents) "); params.put("scopedAgents", scopedAgents);
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
        List<ConversationSummary> items = ids.stream().map(n -> summary(conversations.findById(n.longValue()).orElseThrow(), user)).toList();
        String next = items.isEmpty() ? null : encode(conversations.findById(Long.valueOf(items.get(items.size()-1).conversationId())).orElseThrow());
        return new CursorPage<>(items, next, more);
    }

    @Transactional(readOnly = true)
    public ConversationDetail detail(Long id, AuthenticatedUser user) {
        ConversationEntity c = get(id, user);
        ResourceEntity resource=resources.findById(c.getResourceId()).orElseThrow();
        ChatMessageEntity lastMessage=messages.findFirstByResourceIdOrderByCreatedAtDescIdDesc(resource.getId()).orElse(null);
        return ConversationDetail.of(summary(c, user), V2Mapper.resource(resource,lastMessage));
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

    private ConversationSummary summary(ConversationEntity c, AuthenticatedUser user) {
        ResourceEntity r = resources.findById(c.getResourceId()).orElseThrow();
        ConversationAgentStateEntity state = states.findByConversationIdAndAgentId(c.getId(), user.getAccountRowId()).orElse(null);
        List<ChatMessageEntity> latest = messages.findByConversationIdOrderByCreatedAtDescIdDesc(c.getId(), PageRequest.of(0,1));
        Instant deadline = r.getLastCustomerMessageAt() == null ? null : r.getLastCustomerMessageAt().plusSeconds(86400);
        boolean replyable = c.getStatus() != PersistenceEnums.ConversationStatus.CLOSED && user.hasPermission(Permission.CHAT_REPLY_ASSIGNED)
                && user.getAccountRowId().equals(c.getAssignedAgentId()) && deadline != null && !deadline.isBefore(Instant.now());
        return V2Mapper.conversation(c, r, state, latest.isEmpty() ? null : latest.get(0), replyable);
    }

    private void add(StringBuilder sql, Map<String,Object> params, String column, String name, String value) {
        if (value != null && !value.isBlank()) { sql.append("and ").append(column).append("=:").append(name).append(' '); params.put(name, value); }
    }
    private static String encode(ConversationEntity c) { return CursorCodec.encode(c.getLastMessageAt() == null ? c.getCreatedAt() : c.getLastMessageAt(), c.getId()); }
    private static String encode(Instant at, Long id) { return CursorCodec.encode(at, id); }

    /** Kept as the single service-level entry point for cursor validation. */
    private CursorCodec.Key decode(String cursor) {
        return CursorCodec.decode(cursor);
    }
}
