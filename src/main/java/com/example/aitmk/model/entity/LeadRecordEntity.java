package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/**
 * Local cache of CRM leads_bank worksheet records.
 * customer_phone is the unique lookup key; lead_data stores
 * the full 21-field JSON payload for frontend delivery.
 */
@Getter
@Setter
@Entity
@Table(name = "lead_records")
public class LeadRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_phone", nullable = false, length = 64, unique = true)
    private String customerPhone;

    @Column(name = "crm_row_id", length = 64)
    private String crmRowId;

    @Column(name = "lead_data", columnDefinition = "LONGTEXT")
    private String leadData;

    @Column(name = "crm_synced_at")
    private Instant crmSyncedAt;

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
