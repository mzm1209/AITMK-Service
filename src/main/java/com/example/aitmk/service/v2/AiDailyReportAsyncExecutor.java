package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.AiDailyReportConversationEntity;
import com.example.aitmk.model.entity.AiDailyReportEntity;
import com.example.aitmk.repository.AiDailyReportConversationRepository;
import com.example.aitmk.repository.AiDailyReportRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AiDailyReportAsyncExecutor {

    private final AiDailyReportRepository reports;
    private final AiDailyReportConversationRepository conversations;
    private final AiDailyReportSnapshotService snapshots;
    private final DifyWorkflowClient dify;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Executor executor;

    public AiDailyReportAsyncExecutor(
            AiDailyReportRepository reports,
            AiDailyReportConversationRepository conversations,
            AiDailyReportSnapshotService snapshots,
            DifyWorkflowClient dify,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate,
            @Qualifier("aiDailyReportExecutor") Executor executor) {
        this.reports = reports;
        this.conversations = conversations;
        this.snapshots = snapshots;
        this.dify = dify;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.executor = executor;
    }

    public void completeAfterCommit(Long reportId) {
        Runnable task = () -> executor.execute(() -> complete(reportId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    public void complete(Long reportId) {
        LocalDate reportDate = loadReportDate(reportId);
        try {
            AiDailyReportSnapshotService.SnapshotResult snapshot = snapshots.generate(reportDate);
            saveSnapshot(reportId, snapshot);
            DifyWorkflowClient.DifyWorkflowResult result = dify.runDailyReport(DifyWorkflowClient.DifyDailyReportRequest.from(
                    reportDate,
                    snapshot.reportContextJson(),
                    snapshot.summaryJson(),
                    snapshot.agentStatsJson(),
                    snapshot.conversationJson(),
                    snapshot.dataQualityJson()));
            saveSuccess(reportId, result);
        } catch (Exception ex) {
            saveFailure(reportId, ex);
        }
    }

    private LocalDate loadReportDate(Long reportId) {
        return transactionTemplate.execute(status -> reports.findById(reportId)
                .orElseThrow(() -> new IllegalStateException("AI daily report not found: " + reportId))
                .getReportDate());
    }

    private void saveSnapshot(Long reportId, AiDailyReportSnapshotService.SnapshotResult snapshot) {
        transactionTemplate.executeWithoutResult(status -> {
            AiDailyReportEntity report = findReport(reportId);
            report.setSnapshotJson(snapshot.snapshotJson());
            conversations.deleteAll(conversations.findByReportIdOrderByPriorityScoreDescIdAsc(report.getId()));
            conversations.saveAll(snapshot.topConversations().stream()
                    .map(item -> item.toEntity(report.getId()))
                    .toList());
            reports.save(report);
        });
    }

    private void saveSuccess(Long reportId, DifyWorkflowClient.DifyWorkflowResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            AiDailyReportEntity report = findReport(reportId);
            List<AiDailyReportConversationEntity> rows = conversations.findByReportIdOrderByPriorityScoreDescIdAsc(report.getId());
            applyDifyResult(report, rows, result);
            report.setStatus(AiDailyReportEntity.Status.SUCCESS);
            report.setErrorMessage(null);
            report.setCompletedAt(Instant.now());
            reports.save(report);
            log.info("AI daily report generation succeeded. reportId={}, reportDate={}, version={}, difyRunId={}",
                    report.getId(), report.getReportDate(), report.getVersion(), report.getDifyRunId());
        });
    }

    private void saveFailure(Long reportId, Exception ex) {
        transactionTemplate.executeWithoutResult(status -> {
            AiDailyReportEntity report = findReport(reportId);
            report.setStatus(AiDailyReportEntity.Status.FAILED);
            report.setErrorMessage(truncate(errorMessage(ex), 4000));
            report.setCompletedAt(Instant.now());
            reports.save(report);
            log.warn("AI daily report generation failed. reportId={}, reportDate={}, version={}, error={}",
                    report.getId(), report.getReportDate(), report.getVersion(), report.getErrorMessage());
        });
    }

    private AiDailyReportEntity findReport(Long reportId) {
        return reports.findById(reportId)
                .orElseThrow(() -> new IllegalStateException("AI daily report not found: " + reportId));
    }

    private void applyDifyResult(AiDailyReportEntity report, List<AiDailyReportConversationEntity> rows,
                                 DifyWorkflowClient.DifyWorkflowResult result) {
        JsonNode root = result.result();
        report.setAiResultJson(result.resultJson());
        report.setDifyRunId(result.workflowRunId());
        report.setExecutiveSummary(text(root, "executive_summary", "executiveSummary", "summary"));
        report.setRiskLevel(text(root, "risk_level", "riskLevel"));
        report.setBusinessHealthScore(integer(root, "business_health_score", "businessHealthScore"));
        applyConversationReviews(root, rows);
    }

    private void applyConversationReviews(JsonNode root, List<AiDailyReportConversationEntity> rows) {
        JsonNode reviews = firstArray(root, "conversationReviews", "conversationCases", "conversation_reviews", "conversation_cases");
        if (!reviews.isArray() || reviews.isEmpty()) {
            return;
        }
        Map<String, AiDailyReportConversationEntity> byConversationId = rows.stream()
                .filter(row -> row.getConversationId() != null)
                .collect(Collectors.toMap(row -> row.getConversationId().toString(), row -> row, (a, b) -> a, LinkedHashMap::new));
        List<AiDailyReportConversationEntity> changed = new ArrayList<>();
        for (JsonNode review : reviews) {
            String conversationId = text(review, "conversationId", "conversation_id");
            AiDailyReportConversationEntity row = byConversationId.get(conversationId);
            if (row == null) {
                log.debug("AI daily report conversation review not matched. conversationId={}", conversationId);
                continue;
            }
            row.setAiResultJson(toJson(review));
            changed.add(row);
        }
        if (!changed.isEmpty()) {
            conversations.saveAll(changed);
        }
    }

    private JsonNode firstArray(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (node.isArray()) {
                return node;
            }
        }
        return objectMapper.createArrayNode();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize AI daily report payload", ex);
        }
    }

    private String text(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (!node.isMissingNode() && !node.isNull()) {
                return node.asText();
            }
        }
        return null;
    }

    private Integer integer(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode node = root.path(name);
            if (node.isInt() || node.isLong()) {
                return node.asInt();
            }
            if (node.isNumber()) {
                return node.intValue();
            }
            if (node.isTextual()) {
                try {
                    return Integer.parseInt(node.asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private String errorMessage(Exception ex) {
        if (ex instanceof V2Exception v2) {
            return v2.getCode() + ": " + v2.getMessage();
        }
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
