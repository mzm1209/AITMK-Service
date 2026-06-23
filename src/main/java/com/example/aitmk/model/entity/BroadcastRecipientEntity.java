package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter
@Entity
@Table(name = "broadcast_recipient")
public class BroadcastRecipientEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_id", nullable = false) private Long taskId;
    @Column(name = "customer_phone", nullable = false, length = 32) private String customerPhone;
    @Column(name = "sent_status", nullable = false, length = 32) private String sentStatus = "PENDING";
    @Column(name = "external_message_id", length = 191) private String externalMessageId;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "failed_reason", length = 1000) private String failedReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}