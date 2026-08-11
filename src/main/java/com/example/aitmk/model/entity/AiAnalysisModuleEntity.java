package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity
@Table(name="ai_analysis_module", uniqueConstraints=@UniqueConstraint(name="uk_ai_analysis_module", columnNames={"analysis_id","module_type"}))
public class AiAnalysisModuleEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="analysis_id", nullable=false) private Long analysisId;
    @Column(name="module_type", nullable=false, length=32) private String moduleType;
    @Column(nullable=false, length=32) private String status;
    @Column(name="workflow_run_id", length=191) private String workflowRunId;
    @Column(name="result_json", columnDefinition="LONGTEXT") private String resultJson;
    @Column(name="input_hash", length=64) private String inputHash;
    @Column(name="attempt_count", nullable=false) private int attemptCount;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="error_code", length=128) private String errorCode;
    @Column(name="error_message", length=2000) private String errorMessage;
}
