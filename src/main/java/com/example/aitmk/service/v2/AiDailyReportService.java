package com.example.aitmk.service.v2;

import com.example.aitmk.config.AiDailyReportProperties;
import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.AiDailyReportConversationEntity;
import com.example.aitmk.model.entity.AiDailyReportEntity;
import com.example.aitmk.repository.AiDailyReportConversationRepository;
import com.example.aitmk.repository.AiDailyReportRepository;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AiDailyReportService {

    private static final int DEFAULT_SIZE = 30;
    private static final int MAX_SIZE = 100;

    private final AiDailyReportRepository reports;
    private final AiDailyReportConversationRepository conversations;
    private final AiDailyReportAsyncExecutor asyncExecutor;
    private final AiDailyReportProperties properties;

    @Transactional(readOnly = true)
    public AiDailyReportListView list(AuthenticatedUser user, String reportDate, Integer size) {
        requireReportAccess(user);
        List<AiDailyReportEntity> items = parseOptionalDate(reportDate) == null
                ? reports.findAllByOrderByReportDateDescVersionDesc(PageRequest.of(0, boundedSize(size)))
                : reports.findByReportDateOrderByVersionDesc(parseOptionalDate(reportDate));
        return new AiDailyReportListView(items.stream().map(this::summaryView).toList());
    }

    @Transactional(readOnly = true)
    public AiDailyReportView detail(AuthenticatedUser user, Long id) {
        requireReportAccess(user);
        AiDailyReportEntity report = findReport(id);
        List<AiDailyReportConversationView> conversationViews = conversations.findByReportIdOrderByPriorityScoreDescIdAsc(report.getId())
                .stream()
                .map(this::conversationView)
                .toList();
        return detailView(report, conversationViews);
    }

    @Transactional
    public AiDailyReportView generate(AuthenticatedUser user, AiDailyReportGenerateRequest request) {
        requireReportAccess(user);
        LocalDate reportDate = parseDateOrDefault(request == null ? null : request.reportDate(), defaultReportDate());
        String scope = normalizeScope(request == null ? null : request.scope());
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        return generateInternal(reportDate, scope, AiDailyReportEntity.GenerationType.MANUAL, user.getAccountRowId(), force);
    }

    @Transactional
    public AiDailyReportView generateScheduled(LocalDate reportDate) {
        return generateInternal(reportDate, "all", AiDailyReportEntity.GenerationType.SCHEDULED, "system", false);
    }

    private AiDailyReportView generateInternal(LocalDate reportDate, String scope,
                                               AiDailyReportEntity.GenerationType generationType,
                                               String createdBy, boolean force) {
        if (!force) {
            Optional<AiDailyReportEntity> generating = reports.findFirstByReportDateAndStatusOrderByVersionDesc(
                    reportDate, AiDailyReportEntity.Status.GENERATING);
            if (generating.isPresent()) {
                return viewWithConversations(generating.get());
            }
            Optional<AiDailyReportEntity> success = reports.findFirstByReportDateAndStatusOrderByVersionDesc(
                    reportDate, AiDailyReportEntity.Status.SUCCESS);
            if (success.isPresent()) {
                return viewWithConversations(success.get());
            }
        }
        int nextVersion = reports.findByReportDateOrderByVersionDesc(reportDate).stream()
                .map(AiDailyReportEntity::getVersion)
                .filter(v -> v != null)
                .findFirst()
                .orElse(0) + 1;
        AiDailyReportEntity report = createGenerating(reportDate, nextVersion, generationType, scope, createdBy);
        asyncExecutor.completeAfterCommit(report.getId());
        return viewWithConversations(report);
    }

    @Transactional
    public AiDailyReportView regenerate(AuthenticatedUser user, Long id) {
        requireReportAccess(user);
        AiDailyReportEntity source = findReport(id);
        int nextVersion = reports.findByReportDateOrderByVersionDesc(source.getReportDate()).stream()
                .map(AiDailyReportEntity::getVersion)
                .filter(v -> v != null)
                .findFirst()
                .orElse(0) + 1;
        AiDailyReportEntity report = createGenerating(source.getReportDate(), nextVersion,
                AiDailyReportEntity.GenerationType.REGENERATE, source.getScope(), user.getAccountRowId());
        asyncExecutor.completeAfterCommit(report.getId());
        return viewWithConversations(report);
    }

    private AiDailyReportEntity createGenerating(LocalDate reportDate, int version,
                                                 AiDailyReportEntity.GenerationType generationType,
                                                 String scope, String createdBy) {
        Instant now = Instant.now();
        AiDailyReportEntity report = new AiDailyReportEntity();
        report.setReportDate(reportDate);
        report.setVersion(version);
        report.setStatus(AiDailyReportEntity.Status.GENERATING);
        report.setGenerationType(generationType);
        report.setScope(scope);
        report.setCreatedBy(createdBy);
        report.setStartedAt(now);
        return reports.save(report);
    }

    private AiDailyReportView viewWithConversations(AiDailyReportEntity report) {
        List<AiDailyReportConversationView> conversationViews = conversations.findByReportIdOrderByPriorityScoreDescIdAsc(report.getId())
                .stream()
                .map(this::conversationView)
                .toList();
        return detailView(report, conversationViews);
    }

    private AiDailyReportEntity findReport(Long id) {
        if (id == null) {
            throw invalid("id is required");
        }
        return reports.findById(id)
                .orElseThrow(() -> new V2Exception(HttpStatus.NOT_FOUND, "AI_REPORT_NOT_FOUND", "AI 运营日报不存在"));
    }

    private void requireReportAccess(AuthenticatedUser user) {
        if (user == null || !StringUtils.hasText(user.getAccountRowId())) {
            throw new V2Exception(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或 token 失效");
        }
        if (user.getRole() != AgentRole.OWNER && user.getRole() != AgentRole.MANAGER) {
            throw new V2Exception(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问 AI 运营日报");
        }
    }

    private AiDailyReportSummaryView summaryView(AiDailyReportEntity report) {
        return new AiDailyReportSummaryView(
                report.getId().toString(),
                report.getReportDate().toString(),
                report.getVersion(),
                report.getStatus().name(),
                report.getGenerationType().name(),
                report.getScope(),
                report.getExecutiveSummary(),
                report.getRiskLevel(),
                report.getBusinessHealthScore(),
                report.getCreatedBy(),
                report.getCreatedAt(),
                report.getUpdatedAt(),
                report.getStartedAt(),
                report.getCompletedAt());
    }

    private AiDailyReportView detailView(AiDailyReportEntity report, List<AiDailyReportConversationView> conversationViews) {
        return new AiDailyReportView(
                report.getId().toString(),
                report.getReportDate().toString(),
                report.getVersion(),
                report.getStatus().name(),
                report.getGenerationType().name(),
                report.getScope(),
                report.getSnapshotJson(),
                report.getAiResultJson(),
                report.getExecutiveSummary(),
                report.getRiskLevel(),
                report.getBusinessHealthScore(),
                report.getDifyRunId(),
                report.getErrorMessage(),
                report.getCreatedBy(),
                report.getCreatedAt(),
                report.getUpdatedAt(),
                report.getStartedAt(),
                report.getCompletedAt(),
                conversationViews);
    }

    private AiDailyReportConversationView conversationView(AiDailyReportConversationEntity item) {
        return new AiDailyReportConversationView(
                item.getId().toString(),
                item.getReportId().toString(),
                item.getConversationId() == null ? null : item.getConversationId().toString(),
                item.getCustomerPhone(),
                item.getAgentId(),
                item.getAgentName(),
                item.getMessageCount(),
                item.getCustomerMessageCount(),
                item.getAgentMessageCount(),
                item.getPriorityScore(),
                item.getAppointmentStatus(),
                item.getResolvedStatus(),
                item.getTimeoutCount(),
                item.getConversationSnapshotJson(),
                item.getAiResultJson(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }

    private LocalDate parseOptionalDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return parseDateOrDefault(value, null);
    }

    private LocalDate parseDateOrDefault(String value, LocalDate defaultValue) {
        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw invalid("reportDate 必须是 yyyy-MM-dd");
        }
    }

    private String normalizeScope(String value) {
        if (!StringUtils.hasText(value)) {
            return "all";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("all") && !normalized.equals("managed")) {
            throw invalid("scope 仅支持 all、managed");
        }
        return normalized;
    }

    private int boundedSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(requested, MAX_SIZE);
    }

    private LocalDate defaultReportDate() {
        return LocalDate.now(ZoneId.of(properties.getZone())).minusDays(1);
    }

    private V2Exception invalid(String message) {
        return new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

}
