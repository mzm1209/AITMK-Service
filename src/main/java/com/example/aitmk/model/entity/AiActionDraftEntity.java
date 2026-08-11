package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity
@Table(name="ai_action_draft")
public class AiActionDraftEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="analysis_id", nullable=false) private Long analysisId;
    @Column(name="module_id", nullable=false) private Long moduleId;
    @Column(name="conversation_id", nullable=false) private Long conversationId;
    @Column(name="draft_type", nullable=false, length=32) private String draftType;
    @Column(name="payload_json", nullable=false, columnDefinition="LONGTEXT") private String payloadJson;
    @Column(nullable=false, length=32) private String status = "DRAFT";
    @Column(name="external_row_id", length=191) private String externalRowId;
    @Column(name="idempotency_key", unique=true, length=191) private String idempotencyKey;
    @Column(name="confirmed_by", length=64) private String confirmedBy;
    @Column(name="confirmed_payload_json", columnDefinition="LONGTEXT") private String confirmedPayloadJson;
    @Column(name="confirmed_at") private Instant confirmedAt;
    @Column(name="error_code", length=128) private String errorCode;
    @Column(name="error_message", length=2000) private String errorMessage;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @PrePersist void create(){ Instant now=Instant.now(); if(createdAt==null)createdAt=now; updatedAt=now; }
    @PreUpdate void update(){ updatedAt=Instant.now(); }
}
