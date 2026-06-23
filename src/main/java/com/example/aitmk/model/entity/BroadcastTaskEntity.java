package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter
@Entity
@Table(name = "broadcast_task")
public class BroadcastTaskEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "task_name", nullable = false) private String taskName;
    @Column(name = "status", nullable = false, length = 32) private String status = "DRAFT";
    @Column(name = "total_recipients", nullable = false) private int totalRecipients;
    @Column(name = "sent_count", nullable = false) private int sentCount;
    @Column(name = "delivered_count", nullable = false) private int deliveredCount;
    @Column(name = "read_count", nullable = false) private int readCount;
    @Column(name = "failed_count", nullable = false) private int failedCount;
    @Column(columnDefinition = "TEXT") private String content;
    @Column(name = "media_id", length = 191) private String mediaId;
    @Column(name = "media_type", length = 32) private String mediaType;
    @Column(name = "business_account_id", length = 191) private String businessAccountId;
    @Column(name = "created_by", nullable = false, length = 64) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;
    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}