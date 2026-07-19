package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "ai_daily_report_conversation")
public class AiDailyReportConversationEntity {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "conversation_id")
    private Long conversationId;

    @Column(name = "customer_phone", length = 32)
    private String customerPhone;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "agent_name", length = 128)
    private String agentName;

    @Column(name = "message_count")
    private Integer messageCount = 0;

    @Column(name = "customer_message_count")
    private Integer customerMessageCount = 0;

    @Column(name = "agent_message_count")
    private Integer agentMessageCount = 0;

    @Column(name = "priority_score")
    private Integer priorityScore = 0;

    @Column(name = "appointment_status", length = 64)
    private String appointmentStatus;

    @Column(name = "resolved_status", length = 64)
    private String resolvedStatus;

    @Column(name = "timeout_count")
    private Integer timeoutCount = 0;

    @Column(name = "conversation_snapshot_json", columnDefinition = "LONGTEXT")
    private String conversationSnapshotJson;

    @Column(name = "ai_result_json", columnDefinition = "LONGTEXT")
    private String aiResultJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
