package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity
@Table(name="ai_analysis_trigger")
public class AiAnalysisTriggerEntity {
    @Id @Column(name="conversation_id") private Long conversationId;
    @Column(name="resource_id", nullable=false) private Long resourceId;
    @Column(name="basis_last_message_id", nullable=false) private Long basisLastMessageId;
    @Column(name="scheduled_at", nullable=false) private Instant scheduledAt;
    @Column(nullable=false, length=16) private String status="PENDING";
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    @PrePersist @PreUpdate void touch(){updatedAt=Instant.now();}
}
