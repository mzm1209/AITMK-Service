package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.service.AgentAccountCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DashboardService {
    private static final ZoneId SERVER_ZONE = ZoneId.systemDefault();
    private static final Set<String> RESOLVED_LEAD_STATUSES = Set.of("Paid", "Not qualified", "无效", "Returned");
    private static final String LEAD_STATUS_FIELD_ID = "66b5e34a7e23d13674f24129";

    private final EntityManager em;
    private final V2AccessService access;
    private final AgentAccountCacheService agentAccounts;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public DashboardSummary summary(AuthenticatedUser user, String rawScope) {
        V2AccessService.DataScope scope = access.requireScope(user, rawScope);
        List<String> agents = access.agentsForScope(user, scope);
        if (agents != null && agents.isEmpty()) return new DashboardSummary(0, 0, 0, 0, 0, 0, 0, 0);
        LocalDate today = LocalDate.now(SERVER_ZONE);
        Instant day = today.atStartOfDay(SERVER_ZONE).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(SERVER_ZONE).toInstant();
        Instant now = Instant.now();
        long active = count("conversation", "status<>'CLOSED'", "assigned_agent_id", agents, null);
        long pending = count("business_resource", "resource_status='PENDING_ASSIGNMENT'", "assigned_agent_id", agents, null);
        long unread = count("conversation_agent_state", "unread_count>0", "agent_id", agents, null);
        long expiring = count("business_resource", "last_customer_message_at between :from and :to", "assigned_agent_id", agents,
                new Instant[]{now.minus(Duration.ofHours(24)), now.minus(Duration.ofHours(23))});
        long received = count("conversation", "created_at>=:from", "assigned_agent_id", agents, new Instant[]{day, null});
        long closed = count("conversation", "closed_at>=:from", "assigned_agent_id", agents, new Instant[]{day, null});
        List<Long> responseTimes = firstResponseSeconds(agents, day, dayEnd);
        return new DashboardSummary(active, pending, unread, expiring, received, closed,
                percentile(responseTimes, .5, false) == null ? 0 : percentile(responseTimes, .5, false),
                percentile(responseTimes, .9, false) == null ? 0 : percentile(responseTimes, .9, false));
    }

    @Transactional(readOnly = true)
    public DashboardAnalytics analytics(AuthenticatedUser user, String rawScope, String rawGranularity,
                                        String fromStr, String toStr, String agentId) {
        V2AccessService.DataScope scope = access.requireScope(user, rawScope);
        List<String> scopedAgents = access.agentsForScope(user, scope);
        if (StringUtils.hasText(agentId)) {
            access.requireAgentWithinScope(user, scope, agentId.trim());
            scopedAgents = List.of(agentId.trim());
        }

        Granularity granularity = parseGranularity(rawGranularity);
        LocalDate fromDate = parseDate(fromStr, "from");
        LocalDate toDate = parseDate(toStr, "to");
        if (toDate.isBefore(fromDate)) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "to 不能早于 from");
        }

        Instant from = fromDate.atStartOfDay(SERVER_ZONE).toInstant();
        Instant toExclusive = toDate.plusDays(1).atStartOfDay(SERVER_ZONE).toInstant();
        List<String> buckets = buckets(fromDate, toDate, granularity);

        Map<String, Long> leadByAgent = new HashMap<>();
        Map<String, Long> activeByAgent = new HashMap<>();
        Map<String, Long> resolvedByAgent = new HashMap<>();
        Map<String, List<Long>> firstResponseByAgent = new HashMap<>();
        Map<String, List<Long>> averageResponseByAgent = new HashMap<>();
        Map<String, Long> leadByBucket = zeroLongBuckets(buckets);
        Map<String, List<Long>> firstResponseByBucket = emptyListBuckets(buckets);
        Map<String, List<Long>> averageResponseByBucket = emptyListBuckets(buckets);

        collectLeadCounts(scopedAgents, from, toExclusive, granularity, leadByAgent, leadByBucket);
        collectFirstResponse(scopedAgents, from, toExclusive, granularity, firstResponseByAgent, firstResponseByBucket);
        collectAverageResponse(scopedAgents, from, toExclusive, granularity, averageResponseByAgent, averageResponseByBucket);
        collectActiveConversations(scopedAgents, activeByAgent);
        collectResolvedConversations(scopedAgents, from, toExclusive, resolvedByAgent);

        Set<String> visibleAgents = visibleAgentIds(scopedAgents);
        visibleAgents.addAll(leadByAgent.keySet());
        visibleAgents.addAll(activeByAgent.keySet());
        visibleAgents.addAll(resolvedByAgent.keySet());
        visibleAgents.addAll(firstResponseByAgent.keySet());
        visibleAgents.addAll(averageResponseByAgent.keySet());

        Map<String, String> names = agentAccounts.getNames(visibleAgents);
        List<DashboardAgentStats> agentStats = visibleAgents.stream()
                .sorted(Comparator.nullsLast(String::compareTo))
                .map(id -> new DashboardAgentStats(
                        id,
                        names.get(id),
                        leadByAgent.getOrDefault(id, 0L),
                        averageSeconds(firstResponseByAgent.get(id)),
                        percentile(firstResponseByAgent.get(id), .9, true),
                        averageSeconds(averageResponseByAgent.get(id)),
                        percentile(averageResponseByAgent.get(id), .9, true),
                        activeByAgent.getOrDefault(id, 0L),
                        resolvedByAgent.getOrDefault(id, 0L)))
                .toList();

        List<Long> allFirstResponses = flatten(firstResponseByAgent);
        List<Long> allAverageResponses = flatten(averageResponseByAgent);
        long leadCount = leadByAgent.values().stream().mapToLong(Long::longValue).sum();
        long active = activeByAgent.values().stream().mapToLong(Long::longValue).sum();
        long resolved = resolvedByAgent.values().stream().mapToLong(Long::longValue).sum();
        double averageResolved = agentStats.isEmpty() ? 0.0 : round1((double) resolved / agentStats.size());

        DashboardAnalyticsCards cards = new DashboardAnalyticsCards(
                leadCount,
                averageSeconds(allFirstResponses),
                percentile(allFirstResponses, .5, true),
                percentile(allFirstResponses, .9, true),
                averageSeconds(allAverageResponses),
                percentile(allAverageResponses, .9, true),
                active,
                resolved,
                averageResolved);

        List<LeadTrendPoint> leadTrend = buckets.stream()
                .map(bucket -> new LeadTrendPoint(bucket, leadByBucket.getOrDefault(bucket, 0L)))
                .toList();
        List<ResponseTrendPoint> responseTrend = buckets.stream()
                .map(bucket -> new ResponseTrendPoint(bucket,
                        averageSeconds(firstResponseByBucket.get(bucket)),
                        averageSeconds(averageResponseByBucket.get(bucket))))
                .toList();

        return new DashboardAnalytics(
                scope.name().toLowerCase(Locale.ROOT),
                granularity.value,
                fromDate.toString(),
                toDate.toString(),
                cards,
                leadTrend,
                responseTrend,
                agentStats);
    }

    @Transactional(readOnly = true)
    public CursorPage<AgentStats> agentStats(AuthenticatedUser user, String range, String fromStr, String toStr,
                                             String cursor, int size) {
        V2AccessService.DataScope scope = access.requireScope(user, "mine");
        List<String> agents = access.agentsForScope(user, scope);
        Instant from = parseInstant(fromStr, LocalDate.now(SERVER_ZONE).atStartOfDay(SERVER_ZONE).toInstant());

        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id,
                       count(distinct r.id),
                       sum(case when c.status <> 'CLOSED' then 1 else 0 end),
                       sum(case when c.closed_at >= :from then 1 else 0 end),
                       count(distinct c.id)
                from conversation c
                left join business_resource r on c.resource_id = r.id
                where c.assigned_agent_id is not null
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "c.assigned_agent_id", agents, params);
        sql.append(" group by c.assigned_agent_id order by c.assigned_agent_id");

        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<AgentStats> stats = new ArrayList<>();
        for (Object[] row : rows) {
            String id = str(row[0]);
            List<Long> firstResponses = firstResponseSeconds(List.of(id), from, Instant.now());
            List<Long> averageResponses = averageResponseSeconds(List.of(id), from, Instant.now());
            stats.add(new AgentStats(
                    id,
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue(),
                    percentile(firstResponses, .5, true) == null ? null : percentile(firstResponses, .5, true).doubleValue(),
                    percentile(firstResponses, .9, true) == null ? null : percentile(firstResponses, .9, true).doubleValue(),
                    averageSeconds(averageResponses) == null ? null : averageSeconds(averageResponses).doubleValue(),
                    ((Number) row[4]).longValue()));
        }

        int startIdx = 0;
        if (StringUtils.hasText(cursor)) {
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

    private long count(String table, String where, String agentColumn, List<String> agents, Instant[] range) {
        StringBuilder sql = new StringBuilder("select count(*) from ").append(table).append(" where ").append(where);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, agentColumn, agents, params);
        Query query = em.createNativeQuery(sql.toString());
        bind(query, params);
        if (range != null) {
            query.setParameter("from", ts(range[0]));
            if (range[1] != null) query.setParameter("to", ts(range[1]));
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    private List<Long> firstResponseSeconds(List<String> agents, Instant from, Instant toExclusive) {
        StringBuilder sql = new StringBuilder("""
                select assigned_agent_id, first_customer_message_at, first_agent_reply_at
                from conversation
                where assigned_agent_id is not null
                  and first_customer_message_at is not null
                  and first_agent_reply_at is not null
                  and first_agent_reply_at >= :from
                  and first_agent_reply_at < :to
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "assigned_agent_id", agents, params);
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(row -> secondsBetween(row[1], row[2]))
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private List<Long> averageResponseSeconds(List<String> agents, Instant from, Instant toExclusive) {
        Map<String, List<Long>> byAgent = new HashMap<>();
        Map<String, List<Long>> byBucket = new HashMap<>();
        collectAverageResponse(agents, from, toExclusive, Granularity.DAY, byAgent, byBucket);
        return flatten(byAgent);
    }

    private void collectLeadCounts(List<String> agents, Instant from, Instant toExclusive, Granularity granularity,
                                   Map<String, Long> byAgent, Map<String, Long> byBucket) {
        StringBuilder sql = new StringBuilder("""
                select a.agent_id, a.assigned_at
                from assignment_record a
                where a.assigned_at >= :from
                  and a.assigned_at < :to
                  and not exists (
                    select 1
                    from assignment_record prior
                    where prior.resource_id = a.resource_id
                      and (prior.assigned_at < a.assigned_at
                        or (prior.assigned_at = a.assigned_at and prior.id < a.id))
                  )
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "a.agent_id", agents, params);
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String agent = str(row[0]);
            if (!StringUtils.hasText(agent)) continue;
            byAgent.merge(agent, 1L, Long::sum);
            byBucket.merge(bucket(toInstant(row[1]), granularity), 1L, Long::sum);
        }
    }

    private void collectFirstResponse(List<String> agents, Instant from, Instant toExclusive, Granularity granularity,
                                      Map<String, List<Long>> byAgent, Map<String, List<Long>> byBucket) {
        StringBuilder sql = new StringBuilder("""
                select assigned_agent_id, first_customer_message_at, first_agent_reply_at
                from conversation
                where assigned_agent_id is not null
                  and first_customer_message_at is not null
                  and first_agent_reply_at is not null
                  and first_agent_reply_at >= :from
                  and first_agent_reply_at < :to
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "assigned_agent_id", agents, params);
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String agent = str(row[0]);
            Long seconds = secondsBetween(row[1], row[2]);
            if (!StringUtils.hasText(agent) || seconds == null) continue;
            byAgent.computeIfAbsent(agent, ignored -> new ArrayList<>()).add(seconds);
            byBucket.computeIfAbsent(bucket(toInstant(row[2]), granularity), ignored -> new ArrayList<>()).add(seconds);
        }
    }

    private void collectAverageResponse(List<String> agents, Instant from, Instant toExclusive, Granularity granularity,
                                        Map<String, List<Long>> byAgent, Map<String, List<Long>> byBucket) {
        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id, customer.created_at, min(reply.created_at)
                from chat_message customer
                join conversation c on c.id = customer.conversation_id
                join chat_message reply on reply.conversation_id = customer.conversation_id
                  and reply.created_at > customer.created_at
                  and reply.sender_type in ('AGENT', 'MANAGER')
                where c.assigned_agent_id is not null
                  and customer.sender_type = 'CUSTOMER'
                  and customer.created_at >= :from
                  and customer.created_at < :to
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "c.assigned_agent_id", agents, params);
        sql.append(" group by customer.id, c.assigned_agent_id, customer.created_at");
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) {
            String agent = str(row[0]);
            Long seconds = secondsBetween(row[1], row[2]);
            if (!StringUtils.hasText(agent) || seconds == null) continue;
            byAgent.computeIfAbsent(agent, ignored -> new ArrayList<>()).add(seconds);
            byBucket.computeIfAbsent(bucket(toInstant(row[1]), granularity), ignored -> new ArrayList<>()).add(seconds);
        }
    }

    private void collectActiveConversations(List<String> agents, Map<String, Long> byAgent) {
        StringBuilder sql = new StringBuilder("""
                select assigned_agent_id, count(*)
                from conversation
                where assigned_agent_id is not null
                  and status <> 'CLOSED'
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "assigned_agent_id", agents, params);
        sql.append(" group by assigned_agent_id");
        Query query = em.createNativeQuery(sql.toString());
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) byAgent.put(str(row[0]), ((Number) row[1]).longValue());
    }

    private void collectResolvedConversations(List<String> agents, Instant from, Instant toExclusive, Map<String, Long> byAgent) {
        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id, c.id, lr.lead_data
                from conversation c
                join lead_records lr on lr.customer_phone = c.customer_phone
                where c.assigned_agent_id is not null
                  and lr.updated_at >= :from
                  and lr.updated_at < :to
                """);
        Map<String, Object> params = new HashMap<>();
        appendAgentFilter(sql, "c.assigned_agent_id", agents, params);
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bind(query, params);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Set<String> seen = new HashSet<>();
        for (Object[] row : rows) {
            String agent = str(row[0]);
            String conversationId = str(row[1]);
            if (!StringUtils.hasText(agent) || !seen.add(conversationId)) continue;
            if (RESOLVED_LEAD_STATUSES.contains(extractLeadStatus(str(row[2])))) {
                byAgent.merge(agent, 1L, Long::sum);
            }
        }
    }

    private Set<String> visibleAgentIds(List<String> scopedAgents) {
        if (scopedAgents != null) return new LinkedHashSet<>(scopedAgents);
        @SuppressWarnings("unchecked")
        List<String> ids = em.createNativeQuery("select row_id from agent_accounts").getResultList();
        return new LinkedHashSet<>(ids);
    }

    private void appendAgentFilter(StringBuilder sql, String column, List<String> agents, Map<String, Object> params) {
        if (agents == null) return;
        if (agents.isEmpty()) {
            sql.append(" and 1=0");
            return;
        }
        sql.append(" and ").append(column).append(" in (");
        for (int i = 0; i < agents.size(); i++) {
            if (i > 0) sql.append(", ");
            String name = "agent" + params.size();
            sql.append(":").append(name);
            params.put(name, agents.get(i));
        }
        sql.append(")");
    }

    private void bind(Query query, Map<String, Object> params) {
        params.forEach(query::setParameter);
    }

    private Granularity parseGranularity(String raw) {
        String value = raw == null || raw.isBlank() ? "day" : raw.trim().toLowerCase(Locale.ROOT);
        for (Granularity item : Granularity.values()) {
            if (item.value.equals(value)) return item;
        }
        throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "granularity 仅支持 day、week、month");
    }

    private LocalDate parseDate(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", name + " 不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ex) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", name + " 必须是 yyyy-MM-dd");
        }
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        try {
            return LocalDateTime.parse(value.trim()).atZone(SERVER_ZONE).toInstant();
        } catch (Exception ex) {
            try {
                return LocalDate.parse(value.trim()).atStartOfDay(SERVER_ZONE).toInstant();
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private List<String> buckets(LocalDate from, LocalDate to, Granularity granularity) {
        List<String> result = new ArrayList<>();
        LocalDate cursor = bucketStart(from, granularity);
        while (!cursor.isAfter(to)) {
            result.add(bucket(cursor.atStartOfDay(SERVER_ZONE).toInstant(), granularity));
            cursor = switch (granularity) {
                case DAY -> cursor.plusDays(1);
                case WEEK -> cursor.plusWeeks(1);
                case MONTH -> cursor.plusMonths(1);
            };
        }
        return result;
    }

    private String bucket(Instant instant, Granularity granularity) {
        LocalDate date = LocalDateTime.ofInstant(instant, SERVER_ZONE).toLocalDate();
        return switch (granularity) {
            case DAY -> date.toString();
            case WEEK -> bucketStart(date, granularity).toString();
            case MONTH -> "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
        };
    }

    private LocalDate bucketStart(LocalDate date, Granularity granularity) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    private Map<String, Long> zeroLongBuckets(List<String> buckets) {
        Map<String, Long> result = new LinkedHashMap<>();
        buckets.forEach(bucket -> result.put(bucket, 0L));
        return result;
    }

    private Map<String, List<Long>> emptyListBuckets(List<String> buckets) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        buckets.forEach(bucket -> result.put(bucket, new ArrayList<>()));
        return result;
    }

    private List<Long> flatten(Map<String, List<Long>> values) {
        return values.values().stream().flatMap(Collection::stream).sorted().toList();
    }

    private Long averageSeconds(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private Long percentile(List<Long> values, double p, boolean nullable) {
        if (values == null || values.isEmpty()) return nullable ? null : 0L;
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1));
        return sorted.get(index);
    }

    private Long secondsBetween(Object from, Object to) {
        Instant start = toInstant(from);
        Instant end = toInstant(to);
        if (start == null || end == null || end.isBefore(start)) return null;
        return Duration.between(start, end).getSeconds();
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay(SERVER_ZONE).toInstant();
        if (value instanceof LocalDateTime localDateTime) return localDateTime.atZone(SERVER_ZONE).toInstant();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof ZonedDateTime zonedDateTime) return zonedDateTime.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalArgumentException("Unsupported timestamp type: " + value.getClass());
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String extractLeadStatus(String leadData) {
        if (!StringUtils.hasText(leadData)) return "";
        try {
            JsonNode root = objectMapper.readTree(leadData);
            return firstText(root.path("leadsStatus"),
                    root.path(LEAD_STATUS_FIELD_ID),
                    root.path("线索状态"),
                    root.path("leadStatus"));
        } catch (Exception ex) {
            return "";
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String text = text(node);
            if (StringUtils.hasText(text)) return text.trim();
        }
        return "";
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual() || node.isNumber() || node.isBoolean()) return node.asText();
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.has("name")) return first.path("name").asText("");
            if (first.has("value")) return first.path("value").asText("");
            return first.asText("");
        }
        if (node.has("name")) return node.path("name").asText("");
        if (node.has("value")) return node.path("value").asText("");
        return node.asText("");
    }

    private enum Granularity {
        DAY("day"), WEEK("week"), MONTH("month");
        private final String value;
        Granularity(String value) { this.value = value; }
    }
}
