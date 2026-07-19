package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "ai_daily_report")
public class AiDailyReportEntity {

    public enum Status { GENERATING, SUCCESS, FAILED }
    public enum GenerationType { MANUAL, SCHEDULED, REGENERATE }

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status = Status.GENERATING;

    @Enumerated(EnumType.STRING)
    @Column(name = "generation_type", nullable = false, length = 32)
    private GenerationType generationType = GenerationType.MANUAL;

    @Column(name = "scope", nullable = false, length = 32)
    private String scope = "all";

    @Column(name = "snapshot_json", columnDefinition = "LONGTEXT")
    private String snapshotJson;

    @Column(name = "ai_result_json", columnDefinition = "LONGTEXT")
    private String aiResultJson;

    @Column(name = "executive_summary", columnDefinition = "TEXT")
    private String executiveSummary;

    @Column(name = "risk_level", length = 32)
    private String riskLevel;

    @Column(name = "business_health_score")
    private Integer businessHealthScore;

    @Column(name = "dify_run_id", length = 128)
    private String difyRunId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
