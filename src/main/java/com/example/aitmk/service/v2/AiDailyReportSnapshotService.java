package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiDailyReportProperties;
import com.example.aitmk.model.entity.AiDailyReportConversationEntity;
import com.example.aitmk.model.entity.ChatMessageEntity;
import com.example.aitmk.repository.AgentAccountRepository;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.service.WorkTimeSettingCacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AiDailyReportSnapshotService {

    private static final Set<String> APPOINTMENT_TYPES = Set.of("type A", "type B");
    private static final String RESOLVED_STATUS = "visit";
    private static final String WORK_TIME_START_CONTROL_ID = "69fd6fc2cd23604cb45f095d";
    private static final String WORK_TIME_END_CONTROL_ID = "69fd7074cd23604cb45f0969";
    private static final Set<String> KNOWN_ABNORMAL_AGENT_IDS = Set.of(
            "", "[]", "agent-new", "manager-1", "manager-2", "outside-tmk",
            "owner-1", "tmk-1", "tmk-2");

    private final EntityManager em;
    private final AgentAccountRepository agentAccounts;
    private final ChatMessageRepository messages;
    private final ObjectMapper objectMapper;
    private final AiDailyReportProperties properties;
    private final WorkTimeSettingCacheService workTimeSettings;

    @Transactional(readOnly = true)
    public SnapshotResult generate(LocalDate reportDate) {
        ZoneId reportZone = reportZone();
        WorkSchedule workSchedule = workSchedule(reportZone);
        Instant from = reportDate.atStartOfDay(reportZone).toInstant();
        Instant toExclusive = reportDate.plusDays(1).atStartOfDay(reportZone).toInstant();
        Instant evaluationAt = evaluationAt(reportDate, reportZone, workSchedule);
        List<AgentInfo> realAgents = realAgents();
        List<String> realAgentIds = realAgents.stream().map(AgentInfo::id).toList();
        Map<String, String> agentNames = realAgents.stream()
                .collect(Collectors.toMap(AgentInfo::id, AgentInfo::name, (a, b) -> a, LinkedHashMap::new));

        Metrics metrics = metrics(realAgentIds, from, toExclusive, evaluationAt, workSchedule);
        List<String> abnormalAgents = abnormalAgents(realAgentIds, from, toExclusive);
        List<TopConversation> topConversations = topConversations(realAgentIds, agentNames, from, toExclusive, evaluationAt, workSchedule);
        Map<String, Object> summary = summary(metrics, workSchedule);
        List<Map<String, Object>> agentStats = agentStats(realAgentIds, agentNames, metrics);
        Map<String, Object> dataQuality = dataQuality(abnormalAgents, metrics);
        Map<String, Object> reportContext = reportContext(reportDate, reportZone, workSchedule, evaluationAt);
        String conversationJson = conversationJson(topConversations);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("reportContext", reportContext);
        snapshot.put("reportDate", reportDate.toString());
        snapshot.put("industry", "education_service");
        snapshot.put("businessGoals", List.of("complete_customer_service", "complete_customer_appointment"));
        snapshot.put("businessRules", businessRules());
        snapshot.put("summary", summary);
        snapshot.put("trends", trends(reportDate, metrics));
        snapshot.put("agentStats", agentStats);
        snapshot.put("topConversationCandidates", topConversations.stream().map(TopConversation::candidate).toList());
        snapshot.put("dataQuality", dataQuality);
        snapshot.put("difyInputs", Map.of(
                "report_date", reportDate.toString(),
                "report_context_json", toJson(reportContext),
                "summary_json", toJson(summary),
                "agent_stats_json", toJson(agentStats),
                "conversation_json", conversationJson,
                "data_quality_json", toJson(dataQuality)));

        return new SnapshotResult(toJson(snapshot), toJson(reportContext), toJson(summary), toJson(agentStats),
                conversationJson, toJson(dataQuality), topConversations);
    }

    private ZoneId reportZone() {
        return ZoneId.of(StringUtils.hasText(properties.getZone()) ? properties.getZone() : "Asia/Jakarta");
    }

    private Map<String, Object> reportContext(LocalDate reportDate, ZoneId reportZone, WorkSchedule workSchedule, Instant evaluationAt) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("reportDate", reportDate.toString());
        context.put("businessTimezone", reportZone.toString());
        context.put("reportStatus", StringUtils.hasText(properties.getReportStatus()) ? properties.getReportStatus() : "FINAL");
        context.put("generatedAt", ZonedDateTime.now(reportZone).toString());
        context.put("evaluationAt", evaluationAt.atZone(reportZone).toString());
        context.put("workSchedule", workSchedule.summary());
        context.put("workScheduleSource", workSchedule.source());
        context.put("firstResponseSlaSeconds", properties.getFirstResponseSlaSeconds());
        context.put("timeoutSeconds", properties.getUnrespondedTimeoutSeconds());
        context.put("messagesAlreadyFilteredToReportPeriod", false);
        return context;
    }

    private Map<String, Object> businessRules() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("appointmentLeadTypes", APPOINTMENT_TYPES);
        rules.put("resolvedLeadStatus", RESOLVED_STATUS);
        rules.put("firstResponseSlaSeconds", properties.getFirstResponseSlaSeconds());
        rules.put("timeoutNoResponseSeconds", properties.getUnrespondedTimeoutSeconds());
        rules.put("activeConversationsMeaning", "current_active_conversation_snapshot_not_daily_new_conversations");
        rules.put("reportTimezone", reportZone().toString());
        rules.put("timeoutScope", "ALL_PERIODS_VALID_NEW_LEADS");
        return rules;
    }

    private WorkSchedule workSchedule(ZoneId reportZone) {
        List<WorkWindow> windows = new ArrayList<>();
        JsonNode rows = workTimeSettings.snapshot();
        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                LocalTime start = parseTime(row.path(WORK_TIME_START_CONTROL_ID).asText(""));
                LocalTime end = parseTime(row.path(WORK_TIME_END_CONTROL_ID).asText(""));
                if (start != null && end != null && end.isAfter(start)) {
                    windows.add(new WorkWindow(start, end));
                }
            }
        }
        if (!windows.isEmpty()) {
            windows.sort(Comparator.comparing(WorkWindow::start));
            return new WorkSchedule(reportZone, windows, "CRM");
        }
        WorkWindow fallback = parseWorkWindow(properties.getWorkSchedule());
        return new WorkSchedule(reportZone, List.of(fallback), "CONFIG");
    }

    private WorkWindow parseWorkWindow(String value) {
        if (StringUtils.hasText(value)) {
            String[] parts = value.trim().split("-");
            if (parts.length == 2) {
                LocalTime start = parseTime(parts[0]);
                LocalTime end = parseTime(parts[1]);
                if (start != null && end != null && end.isAfter(start)) {
                    return new WorkWindow(start, end);
                }
            }
        }
        return new WorkWindow(LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    private LocalTime parseTime(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        for (String pattern : List.of("H:mm:ss", "H:mm")) {
            try {
                return LocalTime.parse(trimmed, java.time.format.DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Instant evaluationAt(LocalDate reportDate, ZoneId reportZone, WorkSchedule workSchedule) {
        LocalDate nextDay = reportDate.plusDays(1);
        LocalTime end = workSchedule.windows().stream()
                .map(WorkWindow::end)
                .max(Comparator.naturalOrder())
                .orElse(LocalTime.of(18, 0));
        Instant finalEvaluation = ZonedDateTime.of(nextDay, end, reportZone).toInstant();
        return Instant.now().isBefore(finalEvaluation) ? Instant.now() : finalEvaluation;
    }

    private Map<String, Object> summary(Metrics m, WorkSchedule workSchedule) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("overall", overallSummary(m));
        long overallNewLeads = m.newLeads + m.unassignedNewLeads;
        long assignedActiveConversations = m.activeConversations;
        long overallActiveConversations = assignedActiveConversations + m.unassignedActiveConversations;
        summary.put("newLeads", overallNewLeads);
        summary.put("officialAppointments", m.appointments);
        summary.put("appointments", m.appointments);
        summary.put("appointmentRate", rate(m.appointments, overallNewLeads));
        summary.put("resolvedVisits", m.resolvedVisits);
        summary.put("activeConversations", overallActiveConversations);
        summary.put("closedConversations", null);
        summary.put("firstResponseAvgSeconds", average(allPeriodResponses(m)));
        summary.put("firstResponseP50Seconds", percentile(allPeriodResponses(m), .5));
        summary.put("firstResponseP90Seconds", percentile(allPeriodResponses(m), .9));
        summary.put("averageResponseSeconds", average(allPeriodResponses(m)));
        summary.put("averageResponseP90Seconds", percentile(allPeriodResponses(m), .9));
        summary.put("timeoutConversations", m.timeoutConversations);
        summary.put("timeoutEvents", m.timeoutEvents);
        summary.put("timeoutRate", rate(m.timeoutConversations, m.timeoutDenominator));
        summary.put("timeoutScope", "ALL_PERIODS_VALID_NEW_LEADS");
        summary.put("timeoutDenominator", m.timeoutDenominator);
        summary.put("allPeriods", allPeriodsSummary(m));
        summary.put("workHours", workHoursSummary(m, workSchedule));
        summary.put("offHours", offHoursSummary(m, workSchedule));
        summary.put("backlog", backlogSummary(m));
        return summary;
    }

    private Map<String, Object> overallSummary(Metrics m) {
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("newLeads", m.newLeads + m.unassignedNewLeads);
        overall.put("assignedNewLeads", m.newLeads);
        overall.put("unassignedNewLeads", m.unassignedNewLeads);
        overall.put("officialAppointments", m.appointments);
        overall.put("appointments", m.appointments);
        overall.put("appointmentRate", rate(m.appointments, m.newLeads + m.unassignedNewLeads));
        overall.put("resolvedVisits", m.resolvedVisits);
        overall.put("activeConversations", m.activeConversations + m.unassignedActiveConversations);
        overall.put("assignedActiveConversations", m.activeConversations);
        overall.put("unassignedActiveConversations", m.unassignedActiveConversations);
        overall.put("healthScore", null);
        return overall;
    }

    private Map<String, Object> allPeriodsSummary(Metrics m) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("available", true);
        all.put("status", "OK");
        all.put("populationScope", "ALL_REPORT_NEW_LEADS_WITH_VALID_TIMING");
        all.put("newLeads", m.newLeads + m.unassignedNewLeads);
        all.put("assignedNewLeads", m.newLeads);
        all.put("unassignedNewLeads", m.unassignedNewLeads);
        all.put("firstResponseP50Seconds", percentile(allPeriodResponses(m), .5));
        all.put("firstResponseP90Seconds", percentile(allPeriodResponses(m), .9));
        all.put("averageResponseSeconds", average(allPeriodResponses(m)));
        all.put("timeoutConversations", m.timeoutConversations);
        all.put("timeoutEvents", m.timeoutEvents);
        all.put("timeoutDenominator", m.timeoutDenominator);
        all.put("timeoutRate", rate(m.timeoutConversations, m.timeoutDenominator));
        all.put("timeoutScope", "ALL_PERIODS_VALID_NEW_LEADS");
        return all;
    }

    private Map<String, Object> workHoursSummary(Metrics m, WorkSchedule workSchedule) {
        Map<String, Object> work = new LinkedHashMap<>();
        work.put("available", workSchedule.available());
        work.put("status", workSchedule.available() ? "OK" : "UNAVAILABLE");
        work.put("populationScope", "WORK_HOURS_VALID_NEW_LEADS");
        work.put("newLeads", m.workHoursNewLeads + m.unassignedWorkHoursNewLeads);
        work.put("assignedNewLeads", m.workHoursNewLeads);
        work.put("unassignedNewLeads", m.unassignedWorkHoursNewLeads);
        work.put("firstResponseSlaMetCount", m.workHoursSlaMet);
        work.put("firstResponseSlaRate", rate(m.workHoursSlaMet, m.workHoursSlaDenominator));
        work.put("firstResponseP50Seconds", percentile(m.workHoursBusinessResponses, .5));
        work.put("firstResponseP90Seconds", percentile(m.workHoursBusinessResponses, .9));
        work.put("timeoutConversations", m.workHoursTimeouts);
        work.put("timeoutEvents", m.workHoursTimeoutEvents);
        work.put("timeoutScope", "WORK_HOURS_VALID_NEW_LEADS");
        work.put("timeoutDenominator", m.workHoursSlaDenominator);
        work.put("timeoutRate", rate(m.workHoursTimeouts, m.workHoursSlaDenominator));
        work.put("assignmentDelayP50Seconds", percentile(m.assignmentDelays, .5));
        work.put("agentHandlingDelayP50Seconds", percentile(m.agentHandlingDelays, .5));
        return work;
    }

    private Map<String, Object> offHoursSummary(Metrics m, WorkSchedule workSchedule) {
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("available", workSchedule.available());
        off.put("status", workSchedule.available() ? "OK" : "UNAVAILABLE");
        off.put("populationScope", "OFF_HOURS_VALID_NEW_LEADS");
        off.put("newLeads", m.offHoursNewLeads + m.unassignedOffHoursNewLeads);
        off.put("assignedNewLeads", m.offHoursNewLeads);
        off.put("unassignedNewLeads", m.unassignedOffHoursNewLeads);
        off.put("aiAcknowledgedCount", m.offHoursAiAcknowledged);
        off.put("pendingNextShiftCount", m.offHoursPendingNextShift);
        off.put("nextShiftSlaMetCount", m.offHoursNextShiftSlaMet);
        off.put("nextShiftSlaRate", rate(m.offHoursNextShiftSlaMet, m.offHoursNextShiftDenominator));
        off.put("nextShiftResponseP50Seconds", percentile(m.offHoursNextShiftResponses, .5));
        off.put("nextShiftResponseP90Seconds", percentile(m.offHoursNextShiftResponses, .9));
        off.put("wallClockResponseP50Seconds", percentile(m.offHoursWallClockResponses, .5));
        off.put("timeoutConversations", m.offHoursNextShiftTimeouts);
        off.put("timeoutEvents", m.offHoursNextShiftTimeoutEvents);
        off.put("timeoutScope", "OFF_HOURS_VALID_NEW_LEADS");
        off.put("timeoutDenominator", m.offHoursNextShiftDenominator);
        off.put("timeoutRate", rate(m.offHoursNextShiftTimeouts, m.offHoursNextShiftDenominator));
        return off;
    }

    private Map<String, Object> backlogSummary(Metrics m) {
        Map<String, Object> backlog = new LinkedHashMap<>();
        backlog.put("total", m.activeConversations + m.unassignedActiveConversations);
        backlog.put("assigned", m.activeConversations);
        backlog.put("unassigned", m.unassignedActiveConversations);
        backlog.put("activeMeaning", "current_active_conversation_snapshot_not_daily_new_conversations");
        backlog.put("activeConversationsMeaning", "current_active_conversation_snapshot_not_daily_new_conversations");
        backlog.put("under30Minutes", null);
        backlog.put("between30MinutesAnd2Hours", null);
        backlog.put("between2And24Hours", null);
        backlog.put("over24Hours", null);
        backlog.put("waitingCustomer", null);
        backlog.put("waitingAgent", null);
        backlog.put("unassignedNewLeads", m.unassignedNewLeads);
        return backlog;
    }

    private Map<String, Object> trends(LocalDate reportDate, Metrics m) {
        Map<String, Object> responsePoint = new LinkedHashMap<>();
        responsePoint.put("bucket", reportDate.toString());
        responsePoint.put("firstResponseAvgSeconds", nullableNumber(average(m.firstResponses)));
        responsePoint.put("averageResponseSeconds", nullableNumber(average(m.averageResponses)));
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("leadTrend", List.of(Map.of("bucket", reportDate.toString(), "leadCount", m.newLeads + m.unassignedNewLeads)));
        trends.put("responseTrend", List.of(responsePoint));
        trends.put("appointmentTrend", List.of(Map.of("bucket", reportDate.toString(), "appointments", m.appointments)));
        return trends;
    }

    private List<Map<String, Object>> agentStats(List<String> realAgentIds, Map<String, String> agentNames, Metrics m) {
        Set<String> visible = new TreeSet<>();
        visible.addAll(m.newLeadsByAgent.keySet());
        visible.addAll(m.appointmentsByAgent.keySet());
        visible.addAll(m.resolvedByAgent.keySet());
        visible.addAll(m.activeByAgent.keySet());
        visible.addAll(m.firstResponsesByAgent.keySet());
        visible.addAll(m.averageResponsesByAgent.keySet());
        visible.addAll(m.agentWorkHoursNewLeads.keySet());
        visible.addAll(m.agentOffHoursNewLeads.keySet());
        visible.retainAll(new HashSet<>(realAgentIds));
        List<Map<String, Object>> result = new ArrayList<>();
        for (String agentId : visible) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentId", agentId);
            row.put("agentName", agentNames.get(agentId));
            row.put("role", "TMK");
            row.put("isRealAgent", true);
            row.put("isAssignable", true);
            row.put("onDuty", true);
            row.put("newLeads", m.newLeadsByAgent.getOrDefault(agentId, 0L));
            row.put("assignedNewLeads", m.newLeadsByAgent.getOrDefault(agentId, 0L));
            row.put("appointments", m.appointmentsByAgent.getOrDefault(agentId, 0L));
            row.put("appointmentRate", rate(m.appointmentsByAgent.getOrDefault(agentId, 0L), m.newLeadsByAgent.getOrDefault(agentId, 0L)));
            row.put("resolvedVisits", m.resolvedByAgent.getOrDefault(agentId, 0L));
            row.put("activeConversations", m.activeByAgent.getOrDefault(agentId, 0L));
            row.put("firstResponseAvgSeconds", average(m.firstResponsesByAgent.get(agentId)));
            row.put("firstResponseP90Seconds", percentile(m.firstResponsesByAgent.get(agentId), .9));
            row.put("averageResponseSeconds", average(m.averageResponsesByAgent.get(agentId)));
            row.put("averageResponseP90Seconds", percentile(m.averageResponsesByAgent.get(agentId), .9));
            row.put("timeoutConversations", m.timeoutByAgent.getOrDefault(agentId, 0L));
            row.put("personnelEvaluationStatus", m.agentHandlingDelaysByAgent.containsKey(agentId) ? "AVAILABLE" : "UNAVAILABLE");
            row.put("agentHandlingDelayP50Seconds", percentile(m.agentHandlingDelaysByAgent.get(agentId), .5));
            row.put("overall", agentOverall(agentId, m));
            row.put("allPeriods", agentAllPeriods(agentId, m));
            row.put("workHours", agentPeriod(agentId, m.agentWorkHoursNewLeads, m.agentWorkHoursSlaDenominator,
                    m.agentWorkHoursSlaMet, m.agentWorkHoursTimeouts, m.agentWorkHoursBusinessResponses,
                    "WORK_HOURS_VALID_NEW_LEADS"));
            row.put("offHours", agentOffHours(agentId, m));
            row.put("backlog", Map.of(
                    "total", m.activeByAgent.getOrDefault(agentId, 0L),
                    "assigned", m.activeByAgent.getOrDefault(agentId, 0L),
                    "unassigned", 0L,
                    "activeMeaning", "current_active_conversation_snapshot_not_daily_new_conversations",
                    "activeConversationsMeaning", "current_active_conversation_snapshot_not_daily_new_conversations"));
            result.add(row);
        }
        return result;
    }

    private Map<String, Object> agentOverall(String agentId, Metrics m) {
        long newLeads = m.newLeadsByAgent.getOrDefault(agentId, 0L);
        long active = m.activeByAgent.getOrDefault(agentId, 0L);
        Map<String, Object> overall = new LinkedHashMap<>();
        overall.put("newLeads", newLeads);
        overall.put("assignedNewLeads", newLeads);
        overall.put("unassignedNewLeads", 0L);
        overall.put("officialAppointments", m.appointmentsByAgent.getOrDefault(agentId, 0L));
        overall.put("appointments", m.appointmentsByAgent.getOrDefault(agentId, 0L));
        overall.put("appointmentRate", rate(m.appointmentsByAgent.getOrDefault(agentId, 0L), newLeads));
        overall.put("resolvedVisits", m.resolvedByAgent.getOrDefault(agentId, 0L));
        overall.put("activeConversations", active);
        return overall;
    }

    private Map<String, Object> agentAllPeriods(String agentId, Metrics m) {
        long newLeads = m.newLeadsByAgent.getOrDefault(agentId, 0L);
        long denominator = m.agentWorkHoursSlaDenominator.getOrDefault(agentId, 0L)
                + m.agentOffHoursNextShiftDenominator.getOrDefault(agentId, 0L);
        List<Long> responses = agentAllPeriodResponses(agentId, m);
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("available", true);
        all.put("status", "OK");
        all.put("populationScope", "AGENT_ASSIGNED_VALID_NEW_LEADS");
        all.put("newLeads", newLeads);
        all.put("assignedNewLeads", newLeads);
        all.put("unassignedNewLeads", 0L);
        all.put("firstResponseP50Seconds", percentile(responses, .5));
        all.put("firstResponseP90Seconds", percentile(responses, .9));
        all.put("averageResponseSeconds", average(responses));
        all.put("averageResponseP90Seconds", percentile(responses, .9));
        all.put("timeoutConversations", m.timeoutByAgent.getOrDefault(agentId, 0L));
        all.put("timeoutEvents", m.timeoutEventsByAgent.getOrDefault(agentId, 0L));
        all.put("timeoutDenominator", denominator);
        all.put("timeoutRate", rate(m.timeoutByAgent.getOrDefault(agentId, 0L), denominator));
        all.put("timeoutScope", "AGENT_ASSIGNED_VALID_NEW_LEADS");
        return all;
    }

    private Map<String, Object> agentPeriod(String agentId, Map<String, Long> leadCounts,
                                            Map<String, Long> denominators, Map<String, Long> slaMet,
                                            Map<String, Long> timeouts, Map<String, List<Long>> responses,
                                            String timeoutScope) {
        long denominator = denominators.getOrDefault(agentId, 0L);
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("available", true);
        period.put("status", "OK");
        period.put("populationScope", timeoutScope);
        period.put("newLeads", leadCounts.getOrDefault(agentId, 0L));
        period.put("assignedNewLeads", leadCounts.getOrDefault(agentId, 0L));
        period.put("unassignedNewLeads", 0L);
        period.put("firstResponseSlaMetCount", slaMet.getOrDefault(agentId, 0L));
        period.put("firstResponseSlaRate", rate(slaMet.getOrDefault(agentId, 0L), denominator));
        period.put("firstResponseP50Seconds", percentile(responses.get(agentId), .5));
        period.put("firstResponseP90Seconds", percentile(responses.get(agentId), .9));
        period.put("timeoutConversations", timeouts.getOrDefault(agentId, 0L));
        period.put("timeoutEvents", timeouts.getOrDefault(agentId, 0L));
        period.put("timeoutDenominator", denominator);
        period.put("timeoutRate", rate(timeouts.getOrDefault(agentId, 0L), denominator));
        period.put("timeoutScope", timeoutScope);
        return period;
    }

    private Map<String, Object> agentOffHours(String agentId, Metrics m) {
        Map<String, Object> off = agentPeriod(agentId, m.agentOffHoursNewLeads, m.agentOffHoursNextShiftDenominator,
                m.agentOffHoursNextShiftSlaMet, m.agentOffHoursNextShiftTimeouts,
                m.agentOffHoursNextShiftResponses, "OFF_HOURS_VALID_NEW_LEADS");
        off.put("aiAcknowledgedCount", m.agentOffHoursAiAcknowledged.getOrDefault(agentId, 0L));
        off.put("pendingNextShiftCount", m.agentOffHoursPendingNextShift.getOrDefault(agentId, 0L));
        off.put("nextShiftSlaMetCount", m.agentOffHoursNextShiftSlaMet.getOrDefault(agentId, 0L));
        off.put("nextShiftSlaRate", rate(m.agentOffHoursNextShiftSlaMet.getOrDefault(agentId, 0L),
                m.agentOffHoursNextShiftDenominator.getOrDefault(agentId, 0L)));
        off.put("nextShiftResponseP50Seconds", percentile(m.agentOffHoursNextShiftResponses.get(agentId), .5));
        off.put("nextShiftResponseP90Seconds", percentile(m.agentOffHoursNextShiftResponses.get(agentId), .9));
        off.put("wallClockResponseP50Seconds", percentile(m.agentOffHoursWallClockResponses.get(agentId), .5));
        return off;
    }

    private Map<String, Object> dataQuality(List<String> abnormalAgents, Metrics m) {
        List<String> metricConflicts = metricConflicts(m);
        Set<String> blockingScopes = new LinkedHashSet<>();
        if (m.missingAgentCount > 0) blockingScopes.add("PERSONNEL_EVALUATION");
        if (!metricConflicts.isEmpty()) {
            blockingScopes.add("REPORT_METRICS");
            blockingScopes.add("PERSONNEL_EVALUATION");
        }
        Map<String, Object> dataQuality = new LinkedHashMap<>();
        dataQuality.put("abnormalAgents", abnormalAgents);
        dataQuality.put("missingAppointmentFieldCount", m.missingAppointmentFieldCount);
        dataQuality.put("missingAgentCount", m.missingAgentCount);
        dataQuality.put("missingAgentScope", "REPORT_DAY_NEW_CONVERSATIONS");
        dataQuality.put("missingResponseFactCount", m.missingResponseFactCount);
        dataQuality.put("blockingScopes", new ArrayList<>(blockingScopes));
        dataQuality.put("metricConflicts", metricConflicts);
        dataQuality.put("notes", List.of("activeConversations is a current active conversation snapshot, not daily new conversations"));
        return dataQuality;
    }

    private List<String> metricConflicts(Metrics m) {
        List<String> conflicts = new ArrayList<>();
        long overallNewLeads = m.newLeads + m.unassignedNewLeads;
        long periodNewLeads = m.workHoursNewLeads + m.unassignedWorkHoursNewLeads
                + m.offHoursNewLeads + m.unassignedOffHoursNewLeads;
        if (overallNewLeads != periodNewLeads) {
            conflicts.add("overall.newLeads != workHours.newLeads + offHours.newLeads");
        }
        long backlogTotal = m.activeConversations + m.unassignedActiveConversations;
        if (backlogTotal < m.activeConversations || backlogTotal < m.unassignedActiveConversations) {
            conflicts.add("backlog.total is inconsistent with assigned/unassigned active conversations");
        }
        if (m.timeoutDenominator > 0 && m.timeoutConversations > m.timeoutDenominator) {
            conflicts.add("allPeriods.timeoutConversations > allPeriods.timeoutDenominator");
        }
        if (m.workHoursSlaDenominator > 0 && m.workHoursTimeouts > m.workHoursSlaDenominator) {
            conflicts.add("workHours.timeoutConversations > workHours.timeoutDenominator");
        }
        if (m.offHoursNextShiftDenominator > 0 && m.offHoursNextShiftTimeouts > m.offHoursNextShiftDenominator) {
            conflicts.add("offHours.timeoutConversations > offHours.timeoutDenominator");
        }
        return conflicts;
    }

    private Metrics metrics(List<String> realAgentIds, Instant from, Instant toExclusive, Instant evaluationAt, WorkSchedule workSchedule) {
        Metrics m = new Metrics();
        if (realAgentIds.isEmpty()) {
            return m;
        }
        collectActiveConversations(realAgentIds, m);
        collectFirstResponses(realAgentIds, from, toExclusive, m);
        collectAverageResponses(realAgentIds, from, toExclusive, m);
        collectTimeoutEvents(realAgentIds, from, toExclusive, m);
        m.activeConversations = sum(m.activeByAgent);
        m.missingAppointmentFieldCount = missingAppointmentFieldCount(realAgentIds, from, toExclusive);
        m.unassignedNewLeads = missingAgentCount(from, toExclusive);
        m.missingAgentCount = m.unassignedNewLeads;
        collectUnassignedNewLeadPeriods(from, toExclusive, workSchedule, m);
        m.unassignedActiveConversations = unassignedActiveConversationCount();
        m.missingResponseFactCount = missingResponseFactCount(realAgentIds, from, toExclusive);
        collectConversationFacts(realAgentIds, from, toExclusive, evaluationAt, workSchedule, m);
        m.newLeads = sum(m.newLeadsByAgent);
        m.appointments = sum(m.appointmentsByAgent);
        m.resolvedVisits = sum(m.resolvedByAgent);
        m.timeoutConversations = m.workHoursTimeouts + m.offHoursNextShiftTimeouts;
        m.timeoutDenominator = m.workHoursSlaDenominator + m.offHoursNextShiftDenominator;
        m.workHoursTimeoutEvents = m.workHoursTimeouts;
        m.offHoursNextShiftTimeoutEvents = m.offHoursNextShiftTimeouts;
        if (m.timeoutEvents < m.timeoutConversations) {
            m.timeoutEvents = m.timeoutConversations;
        }
        return m;
    }

    private void collectConversationFacts(List<String> realAgentIds, Instant from, Instant toExclusive,
                                          Instant evaluationAt, WorkSchedule workSchedule, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select c.id,
                       c.resource_id,
                       c.customer_phone,
                       c.assigned_agent_id,
                       c.status,
                       first_assignment.assigned_at,
                       coalesce(first_customer.first_customer_at, c.first_customer_message_at, c.created_at),
                       first_ai.first_ai_reply_at,
                       first_human.first_human_reply_at,
                       lr.leads_type,
                       lr.leads_status
                from conversation c
                join (
                    select a.resource_id, min(a.assigned_at) as assigned_at
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
        appendIn(sql, "a.agent_id", realAgentIds);
        sql.append("""
                    group by a.resource_id
                ) first_assignment on first_assignment.resource_id = c.resource_id
                left join (
                    select conversation_id, min(created_at) as first_customer_at
                    from chat_message
                    where sender_type = 'CUSTOMER'
                    group by conversation_id
                ) first_customer on first_customer.conversation_id = c.id
                left join (
                    select conversation_id, min(created_at) as first_ai_reply_at
                    from chat_message
                    where sender_type = 'AI'
                    group by conversation_id
                ) first_ai on first_ai.conversation_id = c.id
                left join (
                    select customer.conversation_id, min(reply.created_at) as first_human_reply_at
                    from chat_message customer
                    join chat_message reply on reply.conversation_id = customer.conversation_id
                      and reply.created_at > customer.created_at
                      and reply.sender_type in ('AGENT', 'MANAGER')
                    where customer.sender_type = 'CUSTOMER'
                    group by customer.conversation_id
                ) first_human on first_human.conversation_id = c.id
                left join lead_records lr on lr.customer_phone = c.customer_phone
                where first_assignment.assigned_at >= :from
                  and first_assignment.assigned_at < :to
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            long conversationId = number(row[0]);
            String agentId = str(row[3]);
            Instant assignedAt = toInstant(row[5]);
            Instant leadCreatedAt = toInstant(row[6]);
            Instant firstAiResponseAt = toInstant(row[7]);
            Instant firstHumanResponseAt = toInstant(row[8]);
            String leadsType = str(row[9]);
            String leadsStatus = str(row[10]);
            ConversationFact fact = buildConversationFact(conversationId, agentId, assignedAt, leadCreatedAt,
                    firstAiResponseAt, firstHumanResponseAt, leadsType, leadsStatus, from, toExclusive,
                    evaluationAt, workSchedule);
            m.conversationFacts.put(conversationId, fact);
            applyConversationFact(m, fact);
        }
    }

    private ConversationFact buildConversationFact(long conversationId, String agentId, Instant assignedAt,
                                                   Instant leadCreatedAt, Instant firstAiResponseAt,
                                                   Instant firstHumanResponseAt, String leadsType, String leadsStatus,
                                                   Instant reportFrom, Instant reportToExclusive,
                                                   Instant evaluationAt, WorkSchedule workSchedule) {
        boolean workHours = leadCreatedAt != null && workSchedule.isWorking(leadCreatedAt);
        String period = leadCreatedAt == null ? "UNKNOWN" : workHours ? "WORK_HOURS" : "OFF_HOURS";
        Instant responseAt = firstHumanResponseAt;
        Long wallClock = responseAt == null ? null : secondsBetween(leadCreatedAt, responseAt);
        Long businessSeconds = workSchedule.businessSecondsBetween(leadCreatedAt, responseAt);
        Instant nextWorkStartAt = leadCreatedAt == null ? null : workSchedule.nextWorkStart(leadCreatedAt);
        Long nextShiftSeconds = null;
        String timingStatus = "VALID";
        if ("OFF_HOURS".equals(period) && responseAt != null && nextWorkStartAt != null) {
            if (responseAt.isBefore(nextWorkStartAt)) {
                nextShiftSeconds = 0L;
                timingStatus = "PRE_SHIFT_HANDLED";
            } else {
                nextShiftSeconds = Duration.between(nextWorkStartAt, responseAt).getSeconds();
            }
        }
        Long effectivePendingSeconds = workSchedule.businessSecondsBetween(leadCreatedAt, evaluationAt);
        String slaStatus = slaStatus(period, businessSeconds, nextShiftSeconds, effectivePendingSeconds, nextWorkStartAt, evaluationAt);
        boolean timeout = timeoutStatus(period, businessSeconds, nextShiftSeconds, effectivePendingSeconds, slaStatus);
        Long assignmentDelay = secondsBetween(leadCreatedAt, assignedAt);
        Instant handlingStart = assignedAt == null ? leadCreatedAt : assignedAt;
        Long agentHandlingDelay = responseAt == null || handlingStart == null || responseAt.isBefore(handlingStart)
                ? null
                : workSchedule.businessSecondsBetween(handlingStart, responseAt);
        String caseScope = caseScope(leadCreatedAt, reportFrom, reportToExclusive);
        return new ConversationFact(conversationId, agentId, period, slaStatus, timeout, wallClock, businessSeconds,
                nextShiftSeconds, assignmentDelay, agentHandlingDelay, leadCreatedAt, assignedAt, firstAiResponseAt,
                firstHumanResponseAt, nextWorkStartAt, isAppointmentType(leadsType),
                RESOLVED_STATUS.equals(leadsStatus), caseScope, timingStatus);
    }

    private String caseScope(Instant leadCreatedAt, Instant reportFrom, Instant reportToExclusive) {
        if (leadCreatedAt == null) return "UNKNOWN";
        if (!leadCreatedAt.isBefore(reportFrom) && leadCreatedAt.isBefore(reportToExclusive)) return "NEW_LEAD";
        if (leadCreatedAt.isBefore(reportFrom)) return "CARRY_OVER";
        return "UNKNOWN";
    }

    private String slaStatus(String period, Long businessSeconds, Long nextShiftSeconds,
                             Long effectivePendingSeconds, Instant nextWorkStartAt, Instant evaluationAt) {
        long sla = properties.getFirstResponseSlaSeconds();
        if ("UNKNOWN".equals(period)) return "UNKNOWN";
        Long observed = "OFF_HOURS".equals(period) ? nextShiftSeconds : businessSeconds;
        if (observed != null) return observed <= sla ? "MET" : "BREACHED";
        if ("OFF_HOURS".equals(period) && nextWorkStartAt != null && evaluationAt.isBefore(nextWorkStartAt)) return "PENDING";
        if (effectivePendingSeconds == null || effectivePendingSeconds <= sla) return "PENDING";
        return "BREACHED";
    }

    private boolean timeoutStatus(String period, Long businessSeconds, Long nextShiftSeconds,
                                  Long effectivePendingSeconds, String slaStatus) {
        if ("PENDING".equals(slaStatus) || "UNKNOWN".equals(slaStatus)) return false;
        long timeout = properties.getUnrespondedTimeoutSeconds();
        Long observed = "OFF_HOURS".equals(period) ? nextShiftSeconds : businessSeconds;
        if (observed != null) return observed > timeout;
        return effectivePendingSeconds != null && effectivePendingSeconds > timeout;
    }

    private void applyConversationFact(Metrics m, ConversationFact fact) {
        if (!"NEW_LEAD".equals(fact.caseScope())) {
            return;
        }
        m.newLeadsByAgent.merge(fact.agentId(), 1L, Long::sum);
        if (fact.appointment()) m.appointmentsByAgent.merge(fact.agentId(), 1L, Long::sum);
        if (fact.resolved()) m.resolvedByAgent.merge(fact.agentId(), 1L, Long::sum);
        if ("WORK_HOURS".equals(fact.leadCreatedPeriod())) {
            m.workHoursNewLeads++;
            m.agentWorkHoursNewLeads.merge(fact.agentId(), 1L, Long::sum);
            if (!"UNKNOWN".equals(fact.slaStatus()) && !"PENDING".equals(fact.slaStatus())) m.workHoursSlaDenominator++;
            if (!"UNKNOWN".equals(fact.slaStatus()) && !"PENDING".equals(fact.slaStatus())) m.agentWorkHoursSlaDenominator.merge(fact.agentId(), 1L, Long::sum);
            if ("MET".equals(fact.slaStatus())) m.workHoursSlaMet++;
            if ("MET".equals(fact.slaStatus())) m.agentWorkHoursSlaMet.merge(fact.agentId(), 1L, Long::sum);
            if (fact.businessResponseSeconds() != null) m.workHoursBusinessResponses.add(fact.businessResponseSeconds());
            if (fact.businessResponseSeconds() != null) m.agentWorkHoursBusinessResponses.computeIfAbsent(fact.agentId(), ignored -> new ArrayList<>()).add(fact.businessResponseSeconds());
            if (fact.timeoutStatus()) m.workHoursTimeouts++;
            if (fact.timeoutStatus()) {
                m.agentWorkHoursTimeouts.merge(fact.agentId(), 1L, Long::sum);
                m.timeoutByAgent.merge(fact.agentId(), 1L, Long::sum);
            }
        } else if ("OFF_HOURS".equals(fact.leadCreatedPeriod())) {
            m.offHoursNewLeads++;
            m.agentOffHoursNewLeads.merge(fact.agentId(), 1L, Long::sum);
            if (fact.firstAiResponseAt() != null) m.offHoursAiAcknowledged++;
            if (fact.firstAiResponseAt() != null) m.agentOffHoursAiAcknowledged.merge(fact.agentId(), 1L, Long::sum);
            if ("PENDING".equals(fact.slaStatus())) m.offHoursPendingNextShift++;
            if ("PENDING".equals(fact.slaStatus())) m.agentOffHoursPendingNextShift.merge(fact.agentId(), 1L, Long::sum);
            if (!"UNKNOWN".equals(fact.slaStatus()) && !"PENDING".equals(fact.slaStatus())) m.offHoursNextShiftDenominator++;
            if (!"UNKNOWN".equals(fact.slaStatus()) && !"PENDING".equals(fact.slaStatus())) m.agentOffHoursNextShiftDenominator.merge(fact.agentId(), 1L, Long::sum);
            if ("MET".equals(fact.slaStatus())) m.offHoursNextShiftSlaMet++;
            if ("MET".equals(fact.slaStatus())) m.agentOffHoursNextShiftSlaMet.merge(fact.agentId(), 1L, Long::sum);
            if (fact.nextShiftResponseSeconds() != null) m.offHoursNextShiftResponses.add(fact.nextShiftResponseSeconds());
            if (fact.nextShiftResponseSeconds() != null) m.agentOffHoursNextShiftResponses.computeIfAbsent(fact.agentId(), ignored -> new ArrayList<>()).add(fact.nextShiftResponseSeconds());
            if (fact.wallClockResponseSeconds() != null) m.offHoursWallClockResponses.add(fact.wallClockResponseSeconds());
            if (fact.wallClockResponseSeconds() != null) m.agentOffHoursWallClockResponses.computeIfAbsent(fact.agentId(), ignored -> new ArrayList<>()).add(fact.wallClockResponseSeconds());
            if (fact.timeoutStatus()) m.offHoursNextShiftTimeouts++;
            if (fact.timeoutStatus()) {
                m.agentOffHoursNextShiftTimeouts.merge(fact.agentId(), 1L, Long::sum);
                m.timeoutByAgent.merge(fact.agentId(), 1L, Long::sum);
            }
        }
        if (fact.assignmentDelaySeconds() != null) m.assignmentDelays.add(fact.assignmentDelaySeconds());
        if (fact.agentHandlingDelaySeconds() != null) {
            m.agentHandlingDelays.add(fact.agentHandlingDelaySeconds());
            m.agentHandlingDelaysByAgent.computeIfAbsent(fact.agentId(), ignored -> new ArrayList<>()).add(fact.agentHandlingDelaySeconds());
        }
    }

    private void collectNewLeads(List<String> realAgentIds, Instant from, Instant toExclusive, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select a.agent_id, count(*)
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
        appendIn(sql, "a.agent_id", realAgentIds);
        sql.append(" group by a.agent_id");
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            m.newLeadsByAgent.put(str(row[0]), number(row[1]));
        }
    }

    private void collectUnassignedNewLeadPeriods(Instant from, Instant toExclusive, WorkSchedule workSchedule, Metrics m) {
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("""
                select coalesce(first_customer_message_at, created_at)
                from conversation
                where created_at >= :from
                  and created_at < :to
                  and (assigned_agent_id is null or assigned_agent_id = '')
                """)
                .setParameter("from", ts(from))
                .setParameter("to", ts(toExclusive))
                .getResultList();
        for (Object row : rows) {
            Instant leadCreatedAt = toInstant(row);
            if (leadCreatedAt != null && workSchedule.isWorking(leadCreatedAt)) {
                m.unassignedWorkHoursNewLeads++;
            } else {
                m.unassignedOffHoursNewLeads++;
            }
        }
    }

    private void collectLeadOutcomes(List<String> realAgentIds, Instant from, Instant toExclusive, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id, lr.leads_type, lr.leads_status, count(distinct c.id)
                from conversation c
                join (
                    select a.resource_id, min(a.assigned_at) as assigned_at
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
        appendIn(sql, "a.agent_id", realAgentIds);
        sql.append("""
                    group by a.resource_id
                ) first_assignment on first_assignment.resource_id = c.resource_id
                join lead_records lr on lr.customer_phone = c.customer_phone
                where 1=1
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        sql.append(" group by c.assigned_agent_id, lr.leads_type, lr.leads_status");
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            String agent = str(row[0]);
            long count = number(row[3]);
            if (isAppointmentType(str(row[1]))) m.appointmentsByAgent.merge(agent, count, Long::sum);
            if (RESOLVED_STATUS.equals(str(row[2]))) m.resolvedByAgent.merge(agent, count, Long::sum);
        }
    }

    private void collectActiveConversations(List<String> realAgentIds, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select assigned_agent_id, count(*)
                from conversation
                where status <> 'CLOSED'
                """);
        appendIn(sql, "assigned_agent_id", realAgentIds);
        sql.append(" group by assigned_agent_id");
        Query query = em.createNativeQuery(sql.toString());
        bindAgents(query, realAgentIds);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        for (Object[] row : rows) m.activeByAgent.put(str(row[0]), number(row[1]));
    }

    private void collectFirstResponses(List<String> realAgentIds, Instant from, Instant toExclusive, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id,
                       coalesce(message_first.first_customer_at, c.first_customer_message_at),
                       coalesce(message_first.first_reply_at, c.first_agent_reply_at)
                from conversation c
                left join (
                    select customer.conversation_id,
                           min(customer.created_at) as first_customer_at,
                           min(reply.created_at) as first_reply_at
                    from chat_message customer
                    join chat_message reply on reply.conversation_id = customer.conversation_id
                      and reply.created_at > customer.created_at
                      and reply.sender_type in ('AGENT', 'MANAGER')
                    where customer.sender_type = 'CUSTOMER'
                    group by customer.conversation_id
                ) message_first on message_first.conversation_id = c.id
                where coalesce(message_first.first_customer_at, c.first_customer_message_at) is not null
                  and coalesce(message_first.first_reply_at, c.first_agent_reply_at) is not null
                  and coalesce(message_first.first_reply_at, c.first_agent_reply_at) >= :from
                  and coalesce(message_first.first_reply_at, c.first_agent_reply_at) < :to
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            String agent = str(row[0]);
            Long seconds = secondsBetween(row[1], row[2]);
            if (seconds == null) continue;
            m.firstResponses.add(seconds);
            m.firstResponsesByAgent.computeIfAbsent(agent, ignored -> new ArrayList<>()).add(seconds);
        }
    }

    private void collectAverageResponses(List<String> realAgentIds, Instant from, Instant toExclusive, Metrics m) {
        StringBuilder sql = new StringBuilder("""
                select c.assigned_agent_id, customer.created_at, min(reply.created_at)
                from chat_message customer
                join conversation c on c.id = customer.conversation_id
                join chat_message reply on reply.conversation_id = customer.conversation_id
                  and reply.created_at > customer.created_at
                  and reply.sender_type in ('AGENT', 'MANAGER')
                where customer.sender_type = 'CUSTOMER'
                  and customer.created_at >= :from
                  and customer.created_at < :to
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        sql.append(" group by customer.id, c.assigned_agent_id, customer.created_at");
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            String agent = str(row[0]);
            Long seconds = secondsBetween(row[1], row[2]);
            if (seconds == null) continue;
            m.averageResponses.add(seconds);
            m.averageResponsesByAgent.computeIfAbsent(agent, ignored -> new ArrayList<>()).add(seconds);
        }
    }

    private void collectTimeoutEvents(List<String> realAgentIds, Instant from, Instant toExclusive, Metrics m) {
        for (ConversationTimeout row : conversationTimeouts(realAgentIds, from, toExclusive)) {
            if (row.timeoutCount() <= 0) continue;
            m.timeoutEvents += row.timeoutCount();
            m.timeoutEventsByAgent.merge(row.agentId(), row.timeoutCount(), Long::sum);
        }
    }

    private List<TopConversation> topConversations(List<String> realAgentIds, Map<String, String> agentNames,
                                                   Instant from, Instant toExclusive, Instant evaluationAt,
                                                   WorkSchedule workSchedule) {
        if (realAgentIds.isEmpty()) return List.of();
        Map<Long, ConversationTimeout> timeouts = conversationTimeouts(realAgentIds, from, toExclusive).stream()
                .collect(Collectors.toMap(ConversationTimeout::conversationId, Function.identity(), (a, b) -> a));
        Metrics factMetrics = new Metrics();
        collectConversationFacts(realAgentIds, from, toExclusive, evaluationAt, workSchedule, factMetrics);

        StringBuilder sql = new StringBuilder("""
                select c.id, c.customer_phone, c.assigned_agent_id, c.status,
                       count(m.id) as message_count,
                       sum(case when m.sender_type = 'CUSTOMER' then 1 else 0 end) as customer_message_count,
                       sum(case when m.sender_type in ('AGENT', 'MANAGER') then 1 else 0 end) as agent_message_count,
                       lr.leads_type, lr.leads_status
                from conversation c
                join (
                    select a.resource_id, min(a.assigned_at) as assigned_at
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
        appendIn(sql, "a.agent_id", realAgentIds);
        sql.append("""
                    group by a.resource_id
                ) first_assignment on first_assignment.resource_id = c.resource_id
                left join chat_message m on m.conversation_id = c.id
                  and m.created_at >= :from
                  and m.created_at < :to
                left join lead_records lr on lr.customer_phone = c.customer_phone
                where 1=1
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        sql.append("""
                group by c.id, c.customer_phone, c.assigned_agent_id, c.status, lr.leads_type, lr.leads_status
                """);
        List<TopConversation> candidates = new ArrayList<>();
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            long conversationId = number(row[0]);
            String phone = str(row[1]);
            String agentId = str(row[2]);
            String status = str(row[3]);
            long messageCount = number(row[4]);
            long customerMessageCount = number(row[5]);
            long agentMessageCount = number(row[6]);
            String leadsType = str(row[7]);
            String leadsStatus = str(row[8]);
            long timeoutCount = Optional.ofNullable(timeouts.get(conversationId)).map(ConversationTimeout::timeoutCount).orElse(0L);
            ConversationFact fact = factMetrics.conversationFacts.get(conversationId);
            if (fact != null && fact.timeoutStatus()) timeoutCount = Math.max(timeoutCount, 1L);
            boolean appointment = isAppointmentType(leadsType);
            boolean active = !"CLOSED".equals(status);
            long priorityScore = messageCount + (appointment ? 0 : 30) + timeoutCount * 10 + (active ? 10 : 0);
            Map<String, Object> conversationJson = conversationJson(conversationId, phone, agentId, agentNames.get(agentId),
                    status, leadsType, leadsStatus, priorityScore, timeoutCount, fact, from, toExclusive);
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("conversationId", String.valueOf(conversationId));
            candidate.put("customerPhoneMasked", maskPhone(phone));
            candidate.put("agentId", agentId);
            candidate.put("agentName", agentNames.get(agentId));
            candidate.put("messageCount", messageCount);
            candidate.put("customerMessageCount", customerMessageCount);
            candidate.put("agentMessageCount", agentMessageCount);
            candidate.put("priorityScore", priorityScore);
            candidate.put("appointmentStatus", appointment ? "APPOINTED" : "NOT_APPOINTED");
            candidate.put("resolvedStatus", RESOLVED_STATUS.equals(leadsStatus) ? "RESOLVED_VISIT" : "UNRESOLVED");
            candidate.put("timeoutCount", timeoutCount);
            if (fact != null) {
                candidate.putAll(factJson(fact));
            }
            candidates.add(new TopConversation(
                    conversationId, phone, agentId, agentNames.get(agentId), messageCount,
                    customerMessageCount, agentMessageCount, priorityScore, appointment ? "APPOINTED" : "NOT_APPOINTED",
                    RESOLVED_STATUS.equals(leadsStatus) ? "RESOLVED_VISIT" : "UNRESOLVED", timeoutCount,
                    candidate, toJson(conversationJson)));
        }
        return candidates.stream()
                .sorted(Comparator.comparingLong(TopConversation::priorityScore).reversed()
                        .thenComparingLong(TopConversation::conversationId))
                .limit(Math.max(1, properties.getMaxConversationCandidates()))
                .toList();
    }

    private Map<String, Object> conversationJson(long conversationId, String phone, String agentId, String agentName,
                                                 String status, String leadsType, String leadsStatus,
                                                 long priorityScore, long timeoutCount, ConversationFact fact,
                                                 Instant reportFrom, Instant reportToExclusive) {
        List<ChatMessageEntity> allMessages = messages.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId, org.springframework.data.domain.PageRequest.of(0, 1000))
                .stream()
                .sorted(Comparator.comparing(ChatMessageEntity::getCreatedAt).thenComparing(ChatMessageEntity::getId))
                .toList();
        List<ChatMessageEntity> selected = trimMessages(allMessages);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("conversationId", String.valueOf(conversationId));
        json.put("customerPhoneMasked", maskPhone(phone));
        json.put("agentId", agentId);
        json.put("agentName", agentName);
        json.put("status", status);
        json.put("lead", Map.of(
                "appointmentStatus", isAppointmentType(leadsType) ? "APPOINTED" : "NOT_APPOINTED",
                "resolvedStatus", RESOLVED_STATUS.equals(leadsStatus) ? "RESOLVED_VISIT" : "UNRESOLVED"));
        json.put("priorityScore", priorityScore);
        json.put("timeoutCount", timeoutCount);
        if (fact != null) {
            json.putAll(factJson(fact));
        }
        json.put("messageWindow", Map.of(
                "totalMessages", allMessages.size(),
                "includedMessages", selected.size(),
                "truncated", allMessages.size() > selected.size()));
        json.put("messages", selected.stream().map(message -> messageJson(message, reportFrom, reportToExclusive)).toList());
        return json;
    }

    private Map<String, Object> factJson(ConversationFact fact) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("leadCreatedPeriod", fact.leadCreatedPeriod());
        json.put("slaStatus", fact.slaStatus());
        json.put("timingStatus", fact.timingStatus());
        json.put("timeoutStatus", fact.timeoutStatus());
        json.put("wallClockResponseSeconds", fact.wallClockResponseSeconds());
        json.put("businessResponseSeconds", fact.businessResponseSeconds());
        json.put("nextShiftResponseSeconds", fact.nextShiftResponseSeconds());
        json.put("assignmentDelaySeconds", fact.assignmentDelaySeconds());
        json.put("agentHandlingDelaySeconds", fact.agentHandlingDelaySeconds());
        json.put("leadCreatedAt", zonedString(fact.leadCreatedAt()));
        json.put("assignedAt", zonedString(fact.assignedAt()));
        json.put("firstAiResponseAt", zonedString(fact.firstAiResponseAt()));
        json.put("firstHumanResponseAt", zonedString(fact.firstHumanResponseAt()));
        json.put("nextWorkStartAt", zonedString(fact.nextWorkStartAt()));
        json.put("caseScope", fact.caseScope());
        return json;
    }

    private List<ChatMessageEntity> trimMessages(List<ChatMessageEntity> input) {
        int limit = Math.max(1, properties.getMaxMessagesPerConversation());
        if (input.size() <= limit) return input;
        if (limit < 2) return input.subList(input.size() - 1, input.size());
        int head = Math.min(20, limit / 4);
        int tail = limit - head;
        List<ChatMessageEntity> result = new ArrayList<>(limit);
        result.addAll(input.subList(0, head));
        result.addAll(input.subList(input.size() - tail, input.size()));
        return result;
    }

    private Map<String, Object> messageJson(ChatMessageEntity message, Instant reportFrom, Instant reportToExclusive) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("messageId", String.valueOf(message.getId()));
        row.put("senderType", message.getSenderType().name());
        row.put("senderId", message.getSenderId());
        row.put("messageType", message.getMessageType().name());
        row.put("content", sanitizeText(message.getContent()));
        row.put("createdAt", zonedString(message.getCreatedAt()));
        boolean inReportPeriod = message.getCreatedAt() != null
                && !message.getCreatedAt().isBefore(reportFrom)
                && message.getCreatedAt().isBefore(reportToExclusive);
        row.put("inReportPeriod", inReportPeriod);
        row.put("postReportMessage", message.getCreatedAt() != null && !message.getCreatedAt().isBefore(reportToExclusive));
        return row;
    }

    private List<ConversationTimeout> conversationTimeouts(List<String> realAgentIds, Instant from, Instant toExclusive) {
        StringBuilder sql = new StringBuilder("""
                select c.id, c.assigned_agent_id, customer.created_at, min(reply.created_at)
                from chat_message customer
                join conversation c on c.id = customer.conversation_id
                left join chat_message reply on reply.conversation_id = customer.conversation_id
                  and reply.created_at > customer.created_at
                  and reply.sender_type in ('AGENT', 'MANAGER')
                where customer.sender_type = 'CUSTOMER'
                  and customer.created_at >= :from
                  and customer.created_at < :to
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        sql.append(" group by customer.id, c.id, c.assigned_agent_id, customer.created_at");
        Map<Long, ConversationTimeout> byConversation = new LinkedHashMap<>();
        for (Object[] row : query(sql, from, toExclusive, realAgentIds)) {
            long conversationId = number(row[0]);
            String agentId = str(row[1]);
            Instant customerAt = toInstant(row[2]);
            Instant replyAt = toInstant(row[3]);
            boolean timeout = customerAt != null
                    && (replyAt == null ? customerAt.plusSeconds(properties.getUnrespondedTimeoutSeconds()).isBefore(toExclusive)
                    : Duration.between(customerAt, replyAt).getSeconds() > properties.getUnrespondedTimeoutSeconds());
            if (timeout) {
                byConversation.merge(conversationId, new ConversationTimeout(conversationId, agentId, 1),
                        (a, b) -> new ConversationTimeout(a.conversationId(), a.agentId(), a.timeoutCount() + 1));
            }
        }
        return new ArrayList<>(byConversation.values());
    }

    private List<AgentInfo> realAgents() {
        return agentAccounts.findAll().stream()
                .filter(a -> "TMK".equalsIgnoreCase(a.getRole()))
                .filter(a -> isRealAgent(a.getRowId()))
                .filter(a -> StringUtils.hasText(a.getLoginAccount()))
                .map(a -> new AgentInfo(a.getRowId(), a.getLoginAccount()))
                .sorted(Comparator.comparing(AgentInfo::id))
                .toList();
    }

    private boolean isRealAgent(String id) {
        return StringUtils.hasText(id) && !KNOWN_ABNORMAL_AGENT_IDS.contains(id.trim());
    }

    private boolean isAppointmentType(String leadsType) {
        return leadsType != null && APPOINTMENT_TYPES.contains(leadsType);
    }

    private List<String> abnormalAgents(List<String> realAgentIds, Instant from, Instant toExclusive) {
        Set<String> real = new HashSet<>(realAgentIds);
        Set<String> seen = new TreeSet<>();
        seen.addAll(agentAccounts.findAll().stream()
                .map(a -> a.getRowId() == null ? "" : a.getRowId().trim())
                .filter(id -> !isRealAgent(id) || !real.contains(id))
                .filter(id -> KNOWN_ABNORMAL_AGENT_IDS.contains(id) || !StringUtils.hasText(id))
                .toList());
        @SuppressWarnings("unchecked")
        List<String> assigned = em.createNativeQuery("""
                select distinct assigned_agent_id
                from conversation
                where assigned_agent_id is not null
                  and (created_at >= :from or updated_at >= :from or last_message_at >= :from)
                  and created_at < :to
                """)
                .setParameter("from", ts(from))
                .setParameter("to", ts(toExclusive))
                .getResultList();
        for (String id : assigned) {
            if (!real.contains(id)) seen.add(id);
        }
        return seen.stream().filter(Objects::nonNull).toList();
    }

    private long missingAppointmentFieldCount(List<String> realAgentIds, Instant from, Instant toExclusive) {
        StringBuilder sql = new StringBuilder("""
                select count(distinct c.id)
                from conversation c
                left join lead_records lr on lr.customer_phone = c.customer_phone
                where c.created_at >= :from
                  and c.created_at < :to
                  and (lr.leads_type is null or lr.leads_type = '')
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        return scalar(sql, from, toExclusive, realAgentIds);
    }

    private long missingAgentCount(Instant from, Instant toExclusive) {
        return scalar(new StringBuilder("""
                select count(*)
                from conversation
                where created_at >= :from
                  and created_at < :to
                  and (assigned_agent_id is null or assigned_agent_id = '')
                """), from, toExclusive, List.of());
    }

    private long unassignedActiveConversationCount() {
        return number(em.createNativeQuery("""
                select count(*)
                from conversation
                where status <> 'CLOSED'
                  and (assigned_agent_id is null or assigned_agent_id = '')
                """).getSingleResult());
    }

    private long missingResponseFactCount(List<String> realAgentIds, Instant from, Instant toExclusive) {
        StringBuilder sql = new StringBuilder("""
                select count(*)
                from conversation c
                where c.created_at >= :from
                  and c.created_at < :to
                  and c.first_customer_message_at is null
                  and exists (
                    select 1 from chat_message m
                    where m.conversation_id = c.id and m.sender_type = 'CUSTOMER'
                  )
                """);
        appendIn(sql, "c.assigned_agent_id", realAgentIds);
        return scalar(sql, from, toExclusive, realAgentIds);
    }

    private List<Object[]> query(StringBuilder sql, Instant from, Instant toExclusive, List<String> agents) {
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bindAgents(query, agents);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    private long scalar(StringBuilder sql, Instant from, Instant toExclusive, List<String> agents) {
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("from", ts(from));
        query.setParameter("to", ts(toExclusive));
        bindAgents(query, agents);
        return number(query.getSingleResult());
    }

    private void appendIn(StringBuilder sql, String column, List<String> values) {
        if (values.isEmpty()) {
            sql.append(" and 1=0");
            return;
        }
        sql.append(" and ").append(column).append(" in (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(":agent").append(i);
        }
        sql.append(")");
    }

    private void bindAgents(Query query, List<String> agents) {
        for (int i = 0; i < agents.size(); i++) query.setParameter("agent" + i, agents.get(i));
    }

    private Object nullableNumber(Long value) {
        return value == null ? null : value;
    }

    private Long average(List<Long> values) {
        if (values == null || values.isEmpty()) return null;
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private List<Long> allPeriodResponses(Metrics m) {
        List<Long> responses = new ArrayList<>(m.workHoursBusinessResponses.size() + m.offHoursNextShiftResponses.size());
        responses.addAll(m.workHoursBusinessResponses);
        responses.addAll(m.offHoursNextShiftResponses);
        return responses;
    }

    private List<Long> agentAllPeriodResponses(String agentId, Metrics m) {
        List<Long> responses = new ArrayList<>();
        responses.addAll(m.agentWorkHoursBusinessResponses.getOrDefault(agentId, List.of()));
        responses.addAll(m.agentOffHoursNextShiftResponses.getOrDefault(agentId, List.of()));
        return responses;
    }

    private Long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) return null;
        List<Long> sorted = values.stream().sorted().toList();
        int index = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1));
        return sorted.get(index);
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return Math.round((double) numerator * 1000.0 / denominator) / 1000.0;
    }

    private long sum(Map<String, Long> map) {
        return map.values().stream().mapToLong(Long::longValue).sum();
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
        if (value instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay(reportZone()).toInstant();
        if (value instanceof LocalDateTime localDateTime) return localDateTime.atZone(reportZone()).toInstant();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof ZonedDateTime zonedDateTime) return zonedDateTime.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalArgumentException("Unsupported timestamp type: " + value.getClass());
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private String maskPhone(String phone) {
        if (!StringUtils.hasText(phone)) return null;
        String normalized = phone.trim();
        if (normalized.length() <= 4) return "****";
        String suffix = normalized.substring(normalized.length() - 4);
        String prefix = normalized.substring(0, Math.min(3, normalized.length() - 4));
        return prefix + "****" + suffix;
    }

    private String zonedString(Instant instant) {
        return instant == null ? null : instant.atZone(reportZone()).toString();
    }

    private String sanitizeText(String text) {
        if (!StringUtils.hasText(text)) return text;
        String result = text.replaceAll("(?i)https?://[^\\s]*(lookaside\\.fbsbx\\.com|whatsapp|cdn)[^\\s]*", "[MEDIA_URL]");
        result = result.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]");
        result = result.replaceAll("(?<!\\d)(\\+?\\d[\\d\\s-]{7,}\\d)(?!\\d)", "[PHONE]");
        return result;
    }

    private String str(Object value) {
        return value == null ? null : value.toString();
    }

    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize AI daily report snapshot", ex);
        }
    }

    private String conversationJson(List<TopConversation> topConversations) {
        List<JsonNode> payload = topConversations.stream()
                .map(TopConversation::conversationJson)
                .map(this::readTree)
                .toList();
        return toJson(payload);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse conversation snapshot JSON", ex);
        }
    }

    public record SnapshotResult(String snapshotJson,
                                 String reportContextJson,
                                 String summaryJson,
                                 String agentStatsJson,
                                 String conversationJson,
                                 String dataQualityJson,
                                 List<TopConversation> topConversations) {}

    public record TopConversation(
            long conversationId,
            String customerPhone,
            String agentId,
            String agentName,
            long messageCount,
            long customerMessageCount,
            long agentMessageCount,
            long priorityScore,
            String appointmentStatus,
            String resolvedStatus,
            long timeoutCount,
            Map<String, Object> candidate,
            String conversationJson) {
        AiDailyReportConversationEntity toEntity(Long reportId) {
            AiDailyReportConversationEntity entity = new AiDailyReportConversationEntity();
            entity.setReportId(reportId);
            entity.setConversationId(conversationId);
            entity.setCustomerPhone(customerPhone);
            entity.setAgentId(agentId);
            entity.setAgentName(agentName);
            entity.setMessageCount((int) messageCount);
            entity.setCustomerMessageCount((int) customerMessageCount);
            entity.setAgentMessageCount((int) agentMessageCount);
            entity.setPriorityScore((int) priorityScore);
            entity.setAppointmentStatus(appointmentStatus);
            entity.setResolvedStatus(resolvedStatus);
            entity.setTimeoutCount((int) timeoutCount);
            entity.setConversationSnapshotJson(conversationJson);
            return entity;
        }
    }

    private record AgentInfo(String id, String name) {}
    private record ConversationTimeout(long conversationId, String agentId, long timeoutCount) {}
    private record WorkWindow(LocalTime start, LocalTime end) {}
    private record WorkSchedule(ZoneId zone, List<WorkWindow> windows, String source) {
        boolean available() {
            return windows != null && !windows.isEmpty();
        }

        String summary() {
            if (!available()) return null;
            return windows.stream()
                    .map(window -> "%s-%s".formatted(format(window.start()), format(window.end())))
                    .collect(Collectors.joining(","));
        }

        boolean isWorking(Instant instant) {
            if (instant == null || !available()) return false;
            LocalTime time = instant.atZone(zone).toLocalTime();
            return windows.stream().anyMatch(window -> !time.isBefore(window.start()) && time.isBefore(window.end()));
        }

        Instant nextWorkStart(Instant instant) {
            if (instant == null || !available()) return null;
            ZonedDateTime cursor = instant.atZone(zone);
            for (int day = 0; day < 8; day++) {
                LocalDate date = cursor.toLocalDate().plusDays(day);
                for (WorkWindow window : windows) {
                    ZonedDateTime start = ZonedDateTime.of(date, window.start(), zone);
                    ZonedDateTime end = ZonedDateTime.of(date, window.end(), zone);
                    if (cursor.toInstant().isBefore(start.toInstant()) || (!cursor.toInstant().isBefore(start.toInstant()) && cursor.toInstant().isBefore(end.toInstant()))) {
                        return cursor.toInstant().isBefore(start.toInstant()) ? start.toInstant() : cursor.toInstant();
                    }
                }
            }
            return null;
        }

        Long businessSecondsBetween(Instant from, Instant to) {
            if (from == null || to == null || to.isBefore(from) || !available()) return null;
            long seconds = 0;
            LocalDate startDate = from.atZone(zone).toLocalDate();
            LocalDate endDate = to.atZone(zone).toLocalDate();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                for (WorkWindow window : windows) {
                    Instant windowStart = ZonedDateTime.of(date, window.start(), zone).toInstant();
                    Instant windowEnd = ZonedDateTime.of(date, window.end(), zone).toInstant();
                    Instant overlapStart = from.isAfter(windowStart) ? from : windowStart;
                    Instant overlapEnd = to.isBefore(windowEnd) ? to : windowEnd;
                    if (overlapEnd.isAfter(overlapStart)) {
                        seconds += Duration.between(overlapStart, overlapEnd).getSeconds();
                    }
                }
            }
            return seconds;
        }

        private static String format(LocalTime value) {
            return value.toString().length() == 5 ? value.toString() : value.toString().substring(0, 5);
        }
    }

    private record ConversationFact(
            long conversationId,
            String agentId,
            String leadCreatedPeriod,
            String slaStatus,
            boolean timeoutStatus,
            Long wallClockResponseSeconds,
            Long businessResponseSeconds,
            Long nextShiftResponseSeconds,
            Long assignmentDelaySeconds,
            Long agentHandlingDelaySeconds,
            Instant leadCreatedAt,
            Instant assignedAt,
            Instant firstAiResponseAt,
            Instant firstHumanResponseAt,
            Instant nextWorkStartAt,
            boolean appointment,
            boolean resolved,
            String caseScope,
            String timingStatus) {}

    private static final class Metrics {
        long newLeads;
        long appointments;
        long resolvedVisits;
        long activeConversations;
        long unassignedActiveConversations;
        long unassignedNewLeads;
        long timeoutConversations;
        long timeoutEvents;
        long timeoutDenominator;
        long missingAppointmentFieldCount;
        long missingAgentCount;
        long missingResponseFactCount;
        long workHoursNewLeads;
        long unassignedWorkHoursNewLeads;
        long workHoursSlaDenominator;
        long workHoursSlaMet;
        long workHoursTimeouts;
        long workHoursTimeoutEvents;
        long offHoursNewLeads;
        long unassignedOffHoursNewLeads;
        long offHoursAiAcknowledged;
        long offHoursPendingNextShift;
        long offHoursNextShiftDenominator;
        long offHoursNextShiftSlaMet;
        long offHoursNextShiftTimeouts;
        long offHoursNextShiftTimeoutEvents;
        final Map<String, Long> newLeadsByAgent = new HashMap<>();
        final Map<String, Long> appointmentsByAgent = new HashMap<>();
        final Map<String, Long> resolvedByAgent = new HashMap<>();
        final Map<String, Long> activeByAgent = new HashMap<>();
        final Map<String, Long> timeoutByAgent = new HashMap<>();
        final Map<String, Long> timeoutEventsByAgent = new HashMap<>();
        final Map<String, Long> agentWorkHoursNewLeads = new HashMap<>();
        final Map<String, Long> agentWorkHoursSlaDenominator = new HashMap<>();
        final Map<String, Long> agentWorkHoursSlaMet = new HashMap<>();
        final Map<String, Long> agentWorkHoursTimeouts = new HashMap<>();
        final Map<String, Long> agentOffHoursNewLeads = new HashMap<>();
        final Map<String, Long> agentOffHoursAiAcknowledged = new HashMap<>();
        final Map<String, Long> agentOffHoursPendingNextShift = new HashMap<>();
        final Map<String, Long> agentOffHoursNextShiftDenominator = new HashMap<>();
        final Map<String, Long> agentOffHoursNextShiftSlaMet = new HashMap<>();
        final Map<String, Long> agentOffHoursNextShiftTimeouts = new HashMap<>();
        final List<Long> firstResponses = new ArrayList<>();
        final List<Long> averageResponses = new ArrayList<>();
        final List<Long> workHoursBusinessResponses = new ArrayList<>();
        final List<Long> offHoursNextShiftResponses = new ArrayList<>();
        final List<Long> offHoursWallClockResponses = new ArrayList<>();
        final List<Long> assignmentDelays = new ArrayList<>();
        final List<Long> agentHandlingDelays = new ArrayList<>();
        final Map<String, List<Long>> firstResponsesByAgent = new HashMap<>();
        final Map<String, List<Long>> averageResponsesByAgent = new HashMap<>();
        final Map<String, List<Long>> agentHandlingDelaysByAgent = new HashMap<>();
        final Map<String, List<Long>> agentWorkHoursBusinessResponses = new HashMap<>();
        final Map<String, List<Long>> agentOffHoursNextShiftResponses = new HashMap<>();
        final Map<String, List<Long>> agentOffHoursWallClockResponses = new HashMap<>();
        final Map<Long, ConversationFact> conversationFacts = new LinkedHashMap<>();
    }
}
