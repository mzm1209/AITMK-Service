package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.DashboardSummary;
import com.example.aitmk.security.auth.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

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
}
