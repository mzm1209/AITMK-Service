package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Getter @Setter
@Entity
@Table(name = "business_resource")
public class ResourceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(nullable = false) private Long version = 0L;
    @Column(name = "customer_phone", nullable = false, length = 32, unique = true) private String customerPhone;
    @Column(name = "customer_name", length = 128) private String customerName;
    @Enumerated(EnumType.STRING) @Column(name = "source_channel", nullable = false) private SourceChannel sourceChannel = SourceChannel.META;
    @Column(name = "source_external_id", length = 191) private String sourceExternalId;
    @Column(name = "source_campaign_id", length = 191) private String sourceCampaignId;
    @Enumerated(EnumType.STRING) @Column(name = "resource_type", nullable = false) private ResourceType resourceType = ResourceType.NEW_LEAD;
    @Enumerated(EnumType.STRING) @Column(name = "resource_status", nullable = false) private ResourceStatus resourceStatus = ResourceStatus.PENDING_ASSIGNMENT;
    @Column(name = "assigned_agent_id", length = 64) private String assignedAgentId;
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "last_message_at") private Instant lastMessageAt;
    @Column(name = "last_customer_message_at") private Instant lastCustomerMessageAt;
    @Column(name = "last_agent_message_at") private Instant lastAgentMessageAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @PrePersist void prePersist() { Instant now = Instant.now(); if (createdAt == null) createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}
