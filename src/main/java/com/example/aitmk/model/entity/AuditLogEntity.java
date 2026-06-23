package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter
@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "aggregate_type", nullable = false, length = 32) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false) private Long aggregateId;
    @Column(name = "action", nullable = false, length = 64) private String action;
    @Column(name = "operator_id", length = 64) private String operatorId;
    @Column(name = "operator_role", length = 32) private String operatorRole;
    @Column(name = "old_value", length = 1000) private String oldValue;
    @Column(name = "new_value", length = 1000) private String newValue;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}