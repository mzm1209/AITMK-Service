package com.example.aitmk;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.JwtTokenService;
import com.example.aitmk.service.AgentAccountCacheService;
import com.example.aitmk.service.v2.DifyWorkflowClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiDailyReportV2ControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenService tokens;
    @Autowired ObjectMapper objectMapper;
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired AssignmentRecordRepository assignments;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRecordRepository leads;
    @Autowired AiDailyReportConversationRepository reportConversations;
    @Autowired AgentAccountCacheService agentAccounts;
    @Autowired com.example.aitmk.service.v2.AiDailyReportAsyncExecutor asyncExecutor;
    @MockBean DifyWorkflowClient dify;

    @BeforeEach
    void mockDify() throws Exception {
        JsonNode result = objectMapper.readTree("""
                {
                  "executive_summary":"Dify summary",
                  "risk_level":"LOW",
                  "business_health_score":88,
                  "conversationReviews":[]
                }
                """);
        when(dify.runDailyReport(any(DifyWorkflowClient.DifyDailyReportRequest.class)))
                .thenReturn(new DifyWorkflowClient.DifyWorkflowResult(
                        "workflow-run-test",
                        objectMapper.writeValueAsString(result),
                        result));
    }

    @Test
    void ownerCanGenerateListAndReadMockSuccessReport() throws Exception {
        String token = token("ai-report-owner", AgentRole.OWNER, List.of());

        String id = mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportDate":"2026-07-15","scope":"all"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.reportDate").value("2026-07-15"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andExpect(jsonPath("$.data.generationType").value("MANUAL"))
                .andExpect(jsonPath("$.data.conversations", hasSize(0)))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceFirst(".*\"id\":\"([0-9]+)\".*", "$1");

        asyncExecutor.complete(Long.valueOf(id));

        mvc.perform(get("/api/v2/ai-reports/daily?reportDate=2026-07-15")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].id").value(id))
                .andExpect(jsonPath("$.data.items[0].status").value("SUCCESS"));

        mvc.perform(get("/api/v2/ai-reports/daily/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.snapshotJson", containsString("\"industry\":\"education_service\"")))
                .andExpect(jsonPath("$.data.snapshotJson", containsString("\"activeConversationsMeaning\":\"current_active_conversation_snapshot_not_daily_new_conversations\"")))
                .andExpect(jsonPath("$.data.aiResultJson", containsString("\"executive_summary\":\"Dify summary\"")))
                .andExpect(jsonPath("$.data.executiveSummary").value("Dify summary"))
                .andExpect(jsonPath("$.data.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.businessHealthScore").value(88))
                .andExpect(jsonPath("$.data.difyRunId").value("workflow-run-test"));
    }

    @Test
    void managerCanRegenerateAndVersionIncrements() throws Exception {
        String token = token("ai-report-manager", AgentRole.MANAGER, List.of("tmk-1"));
        String id = create(token, "2026-07-14");

        String regeneratedId = mvc.perform(post("/api/v2/ai-reports/daily/" + id + "/regenerate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportDate").value("2026-07-14"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andExpect(jsonPath("$.data.generationType").value("REGENERATE"))
                .andReturn().getResponse().getContentAsString()
                .replaceFirst(".*\"id\":\"([0-9]+)\".*", "$1");

        asyncExecutor.complete(Long.valueOf(regeneratedId));
        mvc.perform(get("/api/v2/ai-reports/daily/" + regeneratedId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));
    }

    @Test
    void tmkCannotAccessAiDailyReports() throws Exception {
        String token = token("ai-report-tmk", AgentRole.TMK, List.of());

        mvc.perform(get("/api/v2/ai-reports/daily")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reportDate":"2026-07-15"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void duplicateGenerateReturnsExistingSuccessUnlessForced() throws Exception {
        String token = token("ai-report-owner-dup", AgentRole.OWNER, List.of());
        String date = "2026-07-13";

        JsonNode first = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + date + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        asyncExecutor.complete(first.path("id").asLong());

        JsonNode duplicate = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + date + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        JsonNode forced = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + date + "\",\"force\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        asyncExecutor.complete(forced.path("id").asLong());

        assertThat(duplicate.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(duplicate.path("version").asInt()).isEqualTo(1);
        assertThat(forced.path("id").asText()).isNotEqualTo(first.path("id").asText());
        assertThat(forced.path("version").asInt()).isEqualTo(2);
        verify(dify, times(2)).runDailyReport(any(DifyWorkflowClient.DifyDailyReportRequest.class));
    }

    @Test
    void difyFailureMarksReportFailedWithErrorMessage() throws Exception {
        when(dify.runDailyReport(any(DifyWorkflowClient.DifyDailyReportRequest.class)))
                .thenThrow(new V2Exception(HttpStatus.BAD_GATEWAY, "DIFY_WORKFLOW_FAILED", "Dify Workflow 调用失败: HTTP 500"));

        JsonNode generated = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-failed", AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"2026-07-12\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andReturn().getResponse().getContentAsString()).path("data");

        asyncExecutor.complete(generated.path("id").asLong());
        mvc.perform(get("/api/v2/ai-reports/daily/" + generated.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-failed", AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.snapshotJson", containsString("\"industry\":\"education_service\"")))
                .andExpect(jsonPath("$.data.errorMessage", containsString("DIFY_WORKFLOW_FAILED")));
    }

    @Test
    void generateBuildsRealSnapshotAndTopFiveConversations() throws Exception {
        String prefix = "air" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String realA = prefix + "-real-a";
        String realB = prefix + "-real-b";
        agentAccounts.upsert(realA, "Alice " + prefix, AgentRole.TMK, List.of());
        agentAccounts.upsert(realB, "Bob " + prefix, AgentRole.TMK, List.of());
        agentAccounts.upsert("tmk-1", "Test TMK", AgentRole.TMK, List.of());
        agentAccounts.upsert("manager-1", "Test Manager", AgentRole.MANAGER, List.of(realA));

        LocalDate reportDate = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        Instant dayStart = reportDate.atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant();

        Fixture top = fixture(prefix + "00011111", realA, dayStart.plusSeconds(600), false);
        lead(top.phone(), null, null);
        assignment(top, realA, dayStart.plusSeconds(610));
        message(top, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(620), "top customer timeout 1");
        message(top, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(620 + 2400), "late reply");
        message(top, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(7200), "top customer timeout 2");
        message(top, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(7300), "extra");
        message(top, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(7400), "quick reply");

        Fixture appointedResolved = fixture(prefix + "00022222", realA, dayStart.plusSeconds(1200), false);
        lead(appointedResolved.phone(), "type A", "visit");
        assignment(appointedResolved, realA, dayStart.plusSeconds(1210));
        message(appointedResolved, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(1220), "appointment");
        message(appointedResolved, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(1280), "fast");

        Fixture noAppointment = fixture(prefix + "00033333", realB, dayStart.plusSeconds(1800), false);
        lead(noAppointment.phone(), "other", "open");
        assignment(noAppointment, realB, dayStart.plusSeconds(1810));
        message(noAppointment, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(1820), "normal");
        message(noAppointment, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(1900), "normal reply");

        for (int i = 0; i < 4; i++) {
            Fixture fixture = fixture(prefix + "00044" + i, i % 2 == 0 ? realA : realB, dayStart.plusSeconds(2400 + i), false);
            lead(fixture.phone(), "type B", "open");
            assignment(fixture, fixture.agent(), dayStart.plusSeconds(2400 + i));
            message(fixture, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(2500 + i), "filler " + i);
            message(fixture, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(2600 + i), "reply " + i);
        }

        Fixture abnormal = fixture(prefix + "00999999", "tmk-1", dayStart.plusSeconds(3600), false);
        lead(abnormal.phone(), "type A", "visit");
        assignment(abnormal, "tmk-1", dayStart.plusSeconds(3610));
        message(abnormal, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(3620), "abnormal");

        String body = mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-" + prefix, AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\",\"scope\":\"all\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode generatedData = objectMapper.readTree(body).path("data");
        asyncExecutor.complete(generatedData.path("id").asLong());
        JsonNode data = objectMapper.readTree(mvc.perform(get("/api/v2/ai-reports/daily/" + generatedData.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-" + prefix, AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.conversations", hasSize(5)))
                .andReturn().getResponse().getContentAsString()).path("data");
        JsonNode snapshot = objectMapper.readTree(data.path("snapshotJson").asText());
        assertThat(snapshot.path("summary").path("overall").path("newLeads").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("overall").path("assignedNewLeads").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("overall").path("officialAppointments").asInt()).isEqualTo(5);
        assertThat(snapshot.path("summary").path("allPeriods").path("populationScope").asText())
                .isEqualTo("ALL_REPORT_NEW_LEADS_WITH_VALID_TIMING");
        assertThat(snapshot.path("summary").path("allPeriods").path("timeoutScope").asText()).isEqualTo("ALL_PERIODS_VALID_NEW_LEADS");
        assertThat(snapshot.path("summary").path("allPeriods").path("timeoutDenominator").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("appointments").asInt()).isEqualTo(5);
        assertThat(snapshot.path("summary").path("resolvedVisits").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("timeoutConversations").asInt()).isZero();
        assertThat(snapshot.path("summary").path("timeoutEvents").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("activeConversations").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("backlog").path("total").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("backlog").path("assigned").asInt()).isEqualTo(7);
        assertThat(snapshot.path("summary").path("backlog").path("unassigned").asInt()).isZero();
        assertThat(snapshot.path("businessRules").path("activeConversationsMeaning").asText())
                .isEqualTo("current_active_conversation_snapshot_not_daily_new_conversations");
        assertThat(snapshot.path("businessRules").path("timeoutScope").asText())
                .isEqualTo("ALL_PERIODS_VALID_NEW_LEADS");
        assertThat(snapshot.path("agentStats").size()).isEqualTo(2);
        assertThat(snapshot.path("agentStats").get(0).has("overall")).isTrue();
        assertThat(snapshot.path("agentStats").get(0).has("workHours")).isTrue();
        assertThat(snapshot.path("agentStats").get(0).has("offHours")).isTrue();
        assertThat(snapshot.path("agentStats").get(0).path("role").asText()).isEqualTo("TMK");
        assertThat(snapshot.path("agentStats").get(0).path("isAssignable").asBoolean()).isTrue();
        assertThat(snapshot.path("agentStats").get(0).path("allPeriods").path("populationScope").asText())
                .isEqualTo("AGENT_ASSIGNED_VALID_NEW_LEADS");
        assertThat(snapshot.path("dataQuality").path("abnormalAgents").toString()).contains("tmk-1");
        assertThat(snapshot.path("topConversationCandidates").size()).isEqualTo(5);
        assertThat(snapshot.path("topConversationCandidates").get(0).path("conversationId").asText())
                .isEqualTo(String.valueOf(top.conversation().getId()));
        assertThat(snapshot.path("topConversationCandidates").get(0).path("priorityScore").asInt())
                .isGreaterThan(snapshot.path("topConversationCandidates").get(1).path("priorityScore").asInt());

        Long reportId = Long.valueOf(data.path("id").asText());
        var rows = reportConversations.findByReportIdOrderByPriorityScoreDescIdAsc(reportId);
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).getConversationId()).isEqualTo(top.conversation().getId());
        assertThat(rows.get(0).getTimeoutCount()).isEqualTo(1);
        assertThat(rows.get(0).getConversationSnapshotJson()).contains("customerPhoneMasked");
        assertThat(rows.get(0).getConversationSnapshotJson()).doesNotContain(top.phone());
    }

    @Test
    void difyConversationReviewsAreSavedToConversationRows() throws Exception {
        String prefix = "airrev" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String realA = prefix + "-real-a";
        agentAccounts.upsert(realA, "Alice " + prefix, AgentRole.TMK, List.of());
        LocalDate reportDate = LocalDate.now(ZoneId.of("Asia/Jakarta"));
        Instant dayStart = reportDate.atStartOfDay(ZoneId.of("Asia/Jakarta")).toInstant();
        Fixture fixture = fixture(prefix + "00111111", realA, dayStart.plusSeconds(600), false);
        lead(fixture.phone(), "other", "open");
        assignment(fixture, realA, dayStart.plusSeconds(610));
        message(fixture, PersistenceEnums.SenderType.CUSTOMER, dayStart.plusSeconds(620), "review me");
        message(fixture, PersistenceEnums.SenderType.AGENT, dayStart.plusSeconds(700), "reply");

        JsonNode result = objectMapper.readTree("""
                {
                  "executive_summary":"reviewed",
                  "risk_level":"MEDIUM",
                  "business_health_score":70,
                  "conversationReviews":[{"conversationId":"%s","risk":"follow_up"}]
                }
                """.formatted(fixture.conversation().getId()));
        when(dify.runDailyReport(any(DifyWorkflowClient.DifyDailyReportRequest.class)))
                .thenReturn(new DifyWorkflowClient.DifyWorkflowResult("workflow-review", objectMapper.writeValueAsString(result), result));

        JsonNode generatedData = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-review", AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andReturn().getResponse().getContentAsString()).path("data");

        asyncExecutor.complete(generatedData.path("id").asLong());
        JsonNode data = objectMapper.readTree(mvc.perform(get("/api/v2/ai-reports/daily/" + generatedData.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-review", AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString()).path("data");

        var rows = reportConversations.findByReportIdOrderByPriorityScoreDescIdAsc(Long.valueOf(data.path("id").asText()));
        assertThat(rows).singleElement().satisfies(row -> assertThat(row.getAiResultJson()).contains("\"risk\":\"follow_up\""));
    }

    @Test
    void snapshotIncludesStructuredWorkAndOffHoursConversationFacts() throws Exception {
        String prefix = "airfact" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String realA = prefix + "-real-a";
        agentAccounts.upsert(realA, "Alice " + prefix, AgentRole.TMK, List.of());
        LocalDate reportDate = LocalDate.of(2026, 7, 15);
        ZoneId zone = ZoneId.of("Asia/Jakarta");

        Fixture workMet = fixture(prefix + "00111111", realA,
                ZonedDateTime.of(reportDate, java.time.LocalTime.of(10, 0), zone).toInstant(), false);
        lead(workMet.phone(), "other", "open");
        assignment(workMet, realA, ZonedDateTime.of(reportDate, java.time.LocalTime.of(10, 0, 30), zone).toInstant());
        message(workMet, PersistenceEnums.SenderType.CUSTOMER, ZonedDateTime.of(reportDate, java.time.LocalTime.of(10, 0), zone).toInstant(), "work lead");
        message(workMet, PersistenceEnums.SenderType.AGENT, ZonedDateTime.of(reportDate, java.time.LocalTime.of(10, 4), zone).toInstant(), "work reply");

        Fixture offMet = fixture(prefix + "00222222", realA,
                ZonedDateTime.of(reportDate, java.time.LocalTime.of(8, 0), zone).toInstant(), false);
        lead(offMet.phone(), "other", "open");
        assignment(offMet, realA, ZonedDateTime.of(reportDate, java.time.LocalTime.of(8, 0, 30), zone).toInstant());
        message(offMet, PersistenceEnums.SenderType.CUSTOMER, ZonedDateTime.of(reportDate, java.time.LocalTime.of(8, 0), zone).toInstant(), "before shift lead");
        message(offMet, PersistenceEnums.SenderType.AGENT, ZonedDateTime.of(reportDate, java.time.LocalTime.of(9, 4), zone).toInstant(), "next shift reply");
        message(offMet, PersistenceEnums.SenderType.AGENT, ZonedDateTime.of(reportDate.plusDays(1), java.time.LocalTime.of(9, 10), zone).toInstant(), "post report follow up");

        JsonNode generatedData = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-facts", AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andReturn().getResponse().getContentAsString()).path("data");

        asyncExecutor.complete(generatedData.path("id").asLong());
        JsonNode data = objectMapper.readTree(mvc.perform(get("/api/v2/ai-reports/daily/" + generatedData.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-facts", AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString()).path("data");

        JsonNode snapshot = objectMapper.readTree(data.path("snapshotJson").asText());
        assertThat(snapshot.path("reportContext").path("businessTimezone").asText()).isEqualTo("Asia/Jakarta");
        assertThat(snapshot.path("reportContext").path("messagesAlreadyFilteredToReportPeriod").asBoolean()).isFalse();
        assertThat(snapshot.path("summary").path("workHours").path("newLeads").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("workHours").path("firstResponseSlaMetCount").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("workHours").path("firstResponseP50Seconds").asInt()).isEqualTo(240);
        assertThat(snapshot.path("summary").path("offHours").path("newLeads").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("offHours").path("nextShiftSlaMetCount").asInt()).isEqualTo(1);
        assertThat(snapshot.path("summary").path("offHours").path("nextShiftResponseP50Seconds").asInt()).isEqualTo(240);
        assertThat(snapshot.path("summary").path("workHours").path("timeoutScope").asText()).isEqualTo("WORK_HOURS_VALID_NEW_LEADS");

        var rows = reportConversations.findByReportIdOrderByPriorityScoreDescIdAsc(data.path("id").asLong());
        String workJson = rows.stream()
                .filter(row -> row.getConversationId().equals(workMet.conversation().getId()))
                .findFirst().orElseThrow().getConversationSnapshotJson();
        String offJson = rows.stream()
                .filter(row -> row.getConversationId().equals(offMet.conversation().getId()))
                .findFirst().orElseThrow().getConversationSnapshotJson();
        JsonNode workConversation = objectMapper.readTree(workJson);
        JsonNode offConversation = objectMapper.readTree(offJson);
        assertThat(workConversation.path("leadCreatedPeriod").asText()).isEqualTo("WORK_HOURS");
        assertThat(workConversation.path("slaStatus").asText()).isEqualTo("MET");
        assertThat(workConversation.path("businessResponseSeconds").asInt()).isEqualTo(240);
        assertThat(offConversation.path("leadCreatedPeriod").asText()).isEqualTo("OFF_HOURS");
        assertThat(offConversation.path("slaStatus").asText()).isEqualTo("MET");
        assertThat(offConversation.path("nextShiftResponseSeconds").asInt()).isEqualTo(240);
        assertThat(offConversation.path("messages").get(0).path("inReportPeriod").asBoolean()).isTrue();
        assertThat(offConversation.path("messages").get(offConversation.path("messages").size() - 1).path("postReportMessage").asBoolean()).isTrue();
        assertThat(offConversation.path("messages").get(offConversation.path("messages").size() - 1).path("inReportPeriod").asBoolean()).isFalse();
    }

    @Test
    void snapshotKeepsAssignedAndUnassignedIdentitiesForV3Contract() throws Exception {
        String prefix = "airv3" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String realA = prefix + "-real-a";
        agentAccounts.upsert(realA, "Alice " + prefix, AgentRole.TMK, List.of());
        LocalDate reportDate = LocalDate.of(2026, 7, 15);
        ZoneId zone = ZoneId.of("Asia/Jakarta");
        Instant at = ZonedDateTime.of(reportDate, java.time.LocalTime.of(10, 0), zone).toInstant();

        Fixture assigned = fixture(prefix + "00111111", realA, at, false);
        lead(assigned.phone(), "other", "open");
        assignment(assigned, realA, at.plusSeconds(30));
        message(assigned, PersistenceEnums.SenderType.CUSTOMER, at, "assigned lead");
        message(assigned, PersistenceEnums.SenderType.AGENT, at.plusSeconds(240), "reply");

        Fixture unassigned = unassignedFixture(prefix + "00222222", at, false);
        lead(unassigned.phone(), "other", "open");
        message(unassigned, PersistenceEnums.SenderType.CUSTOMER, at.plusSeconds(60), "unassigned lead");

        JsonNode generatedData = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-v3", AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andReturn().getResponse().getContentAsString()).path("data");

        asyncExecutor.complete(generatedData.path("id").asLong());
        JsonNode data = objectMapper.readTree(mvc.perform(get("/api/v2/ai-reports/daily/" + generatedData.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-v3", AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString()).path("data");

        JsonNode summary = objectMapper.readTree(data.path("snapshotJson").asText()).path("summary");
        assertThat(summary.path("overall").path("newLeads").asInt()).isEqualTo(2);
        assertThat(summary.path("overall").path("assignedNewLeads").asInt()).isEqualTo(1);
        assertThat(summary.path("overall").path("unassignedNewLeads").asInt()).isEqualTo(1);
        assertThat(summary.path("workHours").path("newLeads").asInt()).isEqualTo(2);
        assertThat(summary.path("workHours").path("assignedNewLeads").asInt()).isEqualTo(1);
        assertThat(summary.path("workHours").path("unassignedNewLeads").asInt()).isEqualTo(1);
        assertThat(summary.path("offHours").path("newLeads").asInt()).isZero();
        assertThat(summary.path("backlog").path("total").asInt()).isEqualTo(2);
        assertThat(summary.path("backlog").path("assigned").asInt()).isEqualTo(1);
        assertThat(summary.path("backlog").path("unassigned").asInt()).isEqualTo(1);
        assertThat(summary.path("overall").path("activeConversations").asInt())
                .isEqualTo(summary.path("backlog").path("total").asInt());
        assertThat(summary.path("dataQuality").isMissingNode()).isTrue();
    }

    @Test
    void carryOverOffHoursConversationHandledBeforeShiftIsNotTimedOut() throws Exception {
        String prefix = "aircarry" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        String realA = prefix + "-real-a";
        agentAccounts.upsert(realA, "Alice " + prefix, AgentRole.TMK, List.of());
        LocalDate reportDate = LocalDate.of(2026, 7, 15);
        ZoneId zone = ZoneId.of("Asia/Jakarta");

        Fixture carryOver = fixture(prefix + "00111111", realA,
                ZonedDateTime.of(reportDate.minusDays(1), java.time.LocalTime.of(22, 40), zone).toInstant(), false);
        lead(carryOver.phone(), "other", "open");
        assignment(carryOver, realA, ZonedDateTime.of(reportDate, java.time.LocalTime.of(6, 53), zone).toInstant());
        message(carryOver, PersistenceEnums.SenderType.CUSTOMER,
                ZonedDateTime.of(reportDate.minusDays(1), java.time.LocalTime.of(22, 40), zone).toInstant(), "night lead");
        message(carryOver, PersistenceEnums.SenderType.AGENT,
                ZonedDateTime.of(reportDate, java.time.LocalTime.of(7, 12), zone).toInstant(), "pre shift reply");

        JsonNode generatedData = objectMapper.readTree(mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token("ai-report-owner-carry", AgentRole.OWNER, List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\",\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("GENERATING"))
                .andReturn().getResponse().getContentAsString()).path("data");

        asyncExecutor.complete(generatedData.path("id").asLong());
        JsonNode data = objectMapper.readTree(mvc.perform(get("/api/v2/ai-reports/daily/" + generatedData.path("id").asText())
                        .header("Authorization", "Bearer " + token("ai-report-owner-carry", AgentRole.OWNER, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString()).path("data");

        var rows = reportConversations.findByReportIdOrderByPriorityScoreDescIdAsc(data.path("id").asLong());
        JsonNode conversation = objectMapper.readTree(rows.get(0).getConversationSnapshotJson());
        assertThat(conversation.path("caseScope").asText()).isEqualTo("CARRY_OVER");
        assertThat(conversation.path("leadCreatedPeriod").asText()).isEqualTo("OFF_HOURS");
        assertThat(conversation.path("timingStatus").asText()).isEqualTo("PRE_SHIFT_HANDLED");
        assertThat(conversation.path("slaStatus").asText()).isEqualTo("MET");
        assertThat(conversation.path("timeoutStatus").asBoolean()).isFalse();
        assertThat(conversation.path("nextShiftResponseSeconds").asLong()).isZero();
        assertThat(conversation.path("messages").get(0).path("inReportPeriod").asBoolean()).isFalse();
        assertThat(conversation.path("messages").get(1).path("inReportPeriod").asBoolean()).isTrue();
    }

    private String create(String token, String reportDate) throws Exception {
        return mvc.perform(post("/api/v2/ai-reports/daily/generate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportDate\":\"" + reportDate + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceFirst(".*\"id\":\"([0-9]+)\".*", "$1");
    }

    private String token(String agentRowId, AgentRole role, List<String> managedAgentIds) {
        return tokens.generateToken(CrmAgentAccount.builder()
                .rowId(agentRowId)
                .loginAccount(agentRowId)
                .role(role)
                .managedAgentIds(managedAgentIds)
                .enabled(true)
                .build());
    }

    private Fixture fixture(String phone, String agent, Instant at, boolean closed) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone(phone);
        resource.setAssignedAgentId(agent);
        resource.setResourceStatus(PersistenceEnums.ResourceStatus.ASSIGNED);
        resource.setCreatedAt(at);
        resource = resources.saveAndFlush(resource);

        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(phone);
        conversation.setAssignedAgentId(agent);
        conversation.setStatus(closed ? PersistenceEnums.ConversationStatus.CLOSED : PersistenceEnums.ConversationStatus.HUMAN_ACTIVE);
        conversation.setFirstCustomerMessageAt(at);
        conversation.setFirstAgentReplyAt(at.plusSeconds(60));
        conversation.setCreatedAt(at);
        conversation = conversations.saveAndFlush(conversation);
        return new Fixture(resource, conversation, phone, agent);
    }

    private Fixture unassignedFixture(String phone, Instant at, boolean closed) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone(phone);
        resource.setResourceStatus(PersistenceEnums.ResourceStatus.PENDING_ASSIGNMENT);
        resource.setCreatedAt(at);
        resource = resources.saveAndFlush(resource);

        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(phone);
        conversation.setStatus(closed ? PersistenceEnums.ConversationStatus.CLOSED : PersistenceEnums.ConversationStatus.HUMAN_ACTIVE);
        conversation.setFirstCustomerMessageAt(at);
        conversation.setCreatedAt(at);
        conversation = conversations.saveAndFlush(conversation);
        return new Fixture(resource, conversation, phone, null);
    }

    private void assignment(Fixture fixture, String agent, Instant at) {
        AssignmentRecordEntity assignment = new AssignmentRecordEntity();
        assignment.setResourceId(fixture.resource().getId());
        assignment.setConversationId(fixture.conversation().getId());
        assignment.setCustomerPhone(fixture.phone());
        assignment.setAgentId(agent);
        assignment.setAssignedBy("system");
        assignment.setAssignType(PersistenceEnums.AssignType.AUTO);
        assignment.setStatus(PersistenceEnums.AssignmentStatus.SERVING);
        assignment.setAssignedAt(at);
        assignments.saveAndFlush(assignment);
    }

    private void message(Fixture fixture, PersistenceEnums.SenderType sender, Instant at, String content) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setResourceId(fixture.resource().getId());
        message.setConversationId(fixture.conversation().getId());
        message.setCustomerPhone(fixture.phone());
        message.setSenderType(sender);
        message.setSenderId(sender == PersistenceEnums.SenderType.CUSTOMER ? null : fixture.agent());
        message.setMessageType(PersistenceEnums.MessageType.TEXT);
        message.setSentStatus(sender == PersistenceEnums.SenderType.CUSTOMER
                ? PersistenceEnums.SentStatus.DELIVERED : PersistenceEnums.SentStatus.SENT);
        message.setContent(content);
        message.setCreatedAt(at);
        messages.saveAndFlush(message);
    }

    private void lead(String phone, String leadsType, String leadsStatus) {
        LeadRecordEntity lead = new LeadRecordEntity();
        lead.setCustomerPhone(phone);
        lead.setCrmRowId("crm-" + phone);
        lead.setLeadData("{}");
        lead.setLeadsType(leadsType);
        lead.setLeadsStatus(leadsStatus);
        leads.saveAndFlush(lead);
    }

    private record Fixture(ResourceEntity resource, ConversationEntity conversation, String phone, String agent) {}
}
