package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Getter @Setter
@Entity
@Table(name = "conversation")
public class ConversationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(nullable = false) private Long version = 0L;
    @Column(name = "resource_id", nullable = false) private Long resourceId;
    @Column(name = "customer_phone", nullable = false, length = 32) private String customerPhone;
    @Column(name = "business_account_id", length = 191) private String businessAccountId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SourceChannel channel = SourceChannel.META;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private ConversationStatus status = ConversationStatus.ACTIVE;
    @Column(name = "assigned_agent_id", length = 64) private String assignedAgentId;
    @Enumerated(EnumType.STRING) @Column(name = "ai_state", nullable = false) private AiState aiState = AiState.NONE;
    @Column(name = "first_customer_message_at") private Instant firstCustomerMessageAt;
    @Column(name = "first_ai_reply_at") private Instant firstAiReplyAt;
    @Column(name = "first_agent_reply_at") private Instant firstAgentReplyAt;
    @Column(name = "last_message_at") private Instant lastMessageAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "closed_by", length = 64) private String closedBy;
    @Column(name = "close_reason", length = 255) private String closeReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "active_resource_id", insertable = false, updatable = false) private Long activeResourceId;

    @PrePersist void prePersist() { Instant now = Instant.now(); if (createdAt == null) createdAt = now; updatedAt = now; }
    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}
