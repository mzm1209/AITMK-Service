package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity
@Table(name = "ai_conversation_analysis")
public class AiConversationAnalysisEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="conversation_id", nullable=false) private Long conversationId;
    @Column(name="resource_id", nullable=false) private Long resourceId;
    @Column(name="lead_row_id", length=191) private String leadRowId;
    @Column(name="assignee_id", length=64) private String assigneeId;
    @Column(name="trigger_type", nullable=false, length=16) private String triggerType;
    @Column(name="basis_last_message_id", nullable=false) private Long basisLastMessageId;
    @Column(name="customer_message_count", nullable=false) private int customerMessageCount;
    @Column(nullable=false, length=32) private String status;
    @Column(name="snapshot_json", nullable=false, columnDefinition="LONGTEXT") private String snapshotJson;
    @Column(name="schema_version", nullable=false, length=32) private String schemaVersion = "1.0";
    @Column(name="request_id", nullable=false, unique=true, length=191) private String requestId;
    @Column(name="created_by", nullable=false, length=64) private String createdBy;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="started_at") private Instant startedAt;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="error_message", length=2000) private String errorMessage;
    @PrePersist void create(){ if(createdAt==null) createdAt=Instant.now(); }
}
