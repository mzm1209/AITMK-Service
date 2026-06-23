package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Getter @Setter
@Entity
@Table(name = "assignment_record")
public class AssignmentRecordEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "resource_id", nullable = false) private Long resourceId;
    @Column(name = "conversation_id") private Long conversationId;
    @Column(name = "customer_phone", nullable = false, length = 32) private String customerPhone;
    @Column(name = "agent_id", nullable = false, length = 64) private String agentId;
    @Column(name = "assigned_by", nullable = false, length = 64) private String assignedBy;
    @Enumerated(EnumType.STRING) @Column(name = "assign_type", nullable = false) private AssignType assignType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AssignmentStatus status = AssignmentStatus.SERVING;
    @Column(nullable = false) private boolean replyable = true;
    @Column(name = "assigned_at", nullable = false) private Instant assignedAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "close_reason", length = 255) private String closeReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "active_resource_id", insertable = false, updatable = false) private Long activeResourceId;
    @PrePersist void prePersist() { Instant now = Instant.now(); if (assignedAt == null) assignedAt = now; if (createdAt == null) createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}
