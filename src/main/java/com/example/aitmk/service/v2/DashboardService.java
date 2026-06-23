package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.DashboardSummary;
import com.example.aitmk.security.auth.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import com.example.aitmk.model.api.v2.V2Api.AgentStats;
import com.example.aitmk.model.api.v2.V2Api.CursorPage;

@Service @RequiredArgsConstructor
public class DashboardService {
    private final EntityManager em;
    private final V2AccessService access;

    @Transactional(readOnly = true)
    public DashboardSummary summary(AuthenticatedUser user, String rawScope) {
        V2AccessService.DataScope scope = access.requireScope(user, rawScope);
        List<String> agents = access.agentsForScope(user, scope);
        if (agents != null && agents.isEmpty()) return new DashboardSummary(0,0,0,0,0,0,0,0);
        Instant day = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant now = Instant.now();
        long active = count("conversation", "status<>'CLOSED'", "assigned_agent_id", agents, null);
        long pending = count("business_resource", "resource_status='PENDING_ASSIGNMENT'", "assigned_agent_id", agents, null);
        long unread = count("conversation_agent_state", "unread_count>0", "agent_id", agents, null);
        long expiring = count("business_resource", "last_customer_message_at between :from and :to", "assigned_agent_id", agents,
                new Instant[]{now.minus(Duration.ofHours(24)), now.minus(Duration.ofHours(23))});
        long received = count("conversation", "created_at>=:from", "assigned_agent_id", agents, new Instant[]{day,null});
        long closed = count("conversation", "closed_at>=:from", "assigned_agent_id", agents, new Instant[]{day,null});
        List<Long> responseTimes = times(agents, day);
        return new DashboardSummary(active,pending,unread,expiring,received,closed,
                percentile(responseTimes,.5)/1000.0, percentile(responseTimes,.9)/1000.0);
    }

    private long count(String table, String where, String agentColumn, List<String> agents, Instant[] range) {
        String scope = agents == null ? "" : " and " + agentColumn + " in (:agents)";
        var query = em.createNativeQuery("select count(*) from " + table + " where " + where + scope);
        if (agents != null) query.setParameter("agents", agents);
        if (range != null) { query.setParameter("from", range[0]); if (range[1] != null) query.setParameter("to", range[1]); }
        return ((Number) query.getSingleResult()).longValue();
    }

    private List<Long> times(List<String> agents, Instant day) {
        String scope = agents == null ? "" : " and assigned_agent_id in (:agents)";
        var query = em.createNativeQuery("select first_customer_message_at,first_agent_reply_at from conversation "
                + "where first_customer_message_at is not null and first_agent_reply_at>=:day" + scope).setParameter("day", day);
        if (agents != null) query.setParameter("agents", agents);
        @SuppressWarnings("unchecked") List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> Duration.between(toInstant(row[0]), toInstant(row[1])).toMillis()).sorted().toList();
    }
    private Instant toInstant(Object value) { return value instanceof java.sql.Timestamp t ? t.toInstant() : (Instant)value; }
    private long percentile(List<Long> values,double p) { return values.isEmpty()?0:values.get(Math.max(0,Math.min(values.size()-1,(int)Math.ceil(values.size()*p)-1))); }

    @Transactional(readOnly = true)
    public CursorPage<AgentStats> agentStats(AuthenticatedUser user, String range, String fromStr, String toStr, String cursor, int size) {
        V2AccessService.DataScope scope = access.requireScope(user, "mine");
        List<String> agents = access.agentsForScope(user, scope);
        
        // Parse time range
        java.time.Instant from = parseInstant(fromStr, java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        java.time.Instant to = parseInstant(toStr, java.time.Instant.now());
        
        // Build the query
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT c.assigned_agent_id, ");
        sql.append("COUNT(DISTINCT r.id) AS totalLeadCount, ");
        sql.append("SUM(CASE WHEN c.status <> 'CLOSED' THEN 1 ELSE 0 END) AS activeConversations, ");
        sql.append("SUM(CASE WHEN c.closed_at >= :from THEN 1 ELSE 0 END) AS todayClosed, ");
        sql.append("COALESCE(AVG(TIMESTAMPDIFF(SECOND, c.first_customer_message_at, c.first_agent_reply_at)), 0) AS avgResponseTime, ");
        sql.append("COUNT(DISTINCT c.id) AS totalServed ");
        sql.append("FROM conversation c ");
        sql.append("LEFT JOIN business_resource r ON c.resource_id = r.id ");
        sql.append("WHERE c.assigned_agent_id IS NOT NULL");
        
        if (agents != null && !agents.isEmpty()) {
            sql.append(" AND c.assigned_agent_id IN (:agents)");
        }
        sql.append(" GROUP BY c.assigned_agent_id");
        sql.append(" ORDER BY c.assigned_agent_id");
        
        var query = em.createNativeQuery(sql.toString());
        query.setParameter("from", from);
        if (agents != null && !agents.isEmpty()) {
            query.setParameter("agents", agents);
        }
        
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        
        // Compute percentiles for each agent
        List<AgentStats> stats = new java.util.ArrayList<>();
        for (Object[] row : rows) {
            String agentId = (String) row[0];
            long totalLead = ((Number) row[1]).longValue();
            long active = ((Number) row[2]).longValue();
            long closed = ((Number) row[3]).longValue();
            double avgResponse = ((Number) row[4]).doubleValue();
            long totalServed = ((Number) row[5]).longValue();
            
            // Get response time distribution for percentile calc
            Double p50 = null;
            Double p90 = null;
            if (totalServed > 0) {
                List<Long> times = responseTimesForAgent(agentId, from);
                if (!times.isEmpty()) {
                    p50 = percentile(times, 0.5) / 1000.0;
                    p90 = percentile(times, 0.9) / 1000.0;
                }
            }
            
            stats.add(new AgentStats(agentId, totalLead, active, closed, p50, p90, avgResponse, totalServed));
        }
        
        // Apply cursor-based pagination
        int startIdx = 0;
        if (cursor != null && !cursor.isBlank()) {
            for (int i = 0; i < stats.size(); i++) {
                if (stats.get(i).agentId().equals(cursor.trim())) {
                    startIdx = i + 1;
                    break;
                }
            }
        }
        
        boolean hasMore = stats.size() > startIdx + size;
        List<AgentStats> page = stats.subList(startIdx, Math.min(startIdx + size, stats.size()));
        String nextCursor = page.isEmpty() ? null : page.get(page.size() - 1).agentId();
        
        return new CursorPage<>(page, hasMore ? nextCursor : null, hasMore);
    }
    
    private java.time.Instant parseInstant(String str, java.time.Instant fallback) {
        if (str == null || str.isBlank()) return fallback;
        try {
            return java.time.LocalDateTime.parse(str.trim(), java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant();
        } catch (Exception e) {
            try {
                return java.time.LocalDate.parse(str.trim()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            } catch (Exception e2) {
                return fallback;
            }
        }
    }
    
    private List<Long> responseTimesForAgent(String agentId, java.time.Instant from) {
        try {
            var q = em.createNativeQuery("SELECT TIMESTAMPDIFF(MICROSECOND, first_customer_message_at, first_agent_reply_at) " +
                    "FROM conversation WHERE assigned_agent_id = :aid AND first_customer_message_at IS NOT NULL " +
                    "AND first_agent_reply_at >= :from AND first_agent_reply_at IS NOT NULL");
            q.setParameter("aid", agentId);
            q.setParameter("from", from);
            @SuppressWarnings("unchecked")
            List<Number> raw = q.getResultList();
            return raw.stream().map(Number::longValue).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

}
