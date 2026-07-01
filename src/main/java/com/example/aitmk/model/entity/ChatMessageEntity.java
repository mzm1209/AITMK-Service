package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import static com.example.aitmk.model.entity.PersistenceEnums.*;

@Getter @Setter
@Entity
@Table(name = "chat_message")
public class ChatMessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "conversation_id", nullable = false) private Long conversationId;
    @Column(name = "resource_id", nullable = false) private Long resourceId;
    @Column(name = "customer_phone", nullable = false, length = 32) private String customerPhone;
    @Column(name = "business_account_id", length = 191) private String businessAccountId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SourceChannel channel = SourceChannel.META;
    @Column(name = "external_message_id", length = 191, unique = true) private String externalMessageId;
    @Column(name = "client_request_id", length = 191, unique = true) private String clientRequestId;
    @Enumerated(EnumType.STRING) @Column(name = "sender_type", nullable = false) private SenderType senderType;
    @Column(name = "sender_id", length = 64) private String senderId;
    @Column(name = "operator_role", length = 32) private String operatorRole;
    @Enumerated(EnumType.STRING) @Column(name = "message_type", nullable = false) private MessageType messageType = MessageType.TEXT;
    @Column(columnDefinition = "LONGTEXT") private String content;
    @Column(name = "media_id", length = 191) private String mediaId;
    @Column(name = "media_url", length = 1024) private String mediaUrl;
    @Column(name = "mime_type", length = 128) private String mimeType;
    @Column(name = "file_name", length = 255) private String fileName;
    @Column(name = "raw_payload", columnDefinition = "LONGTEXT") private String rawPayload;
    @Enumerated(EnumType.STRING) @Column(name = "sent_status", nullable = false) private SentStatus sentStatus = SentStatus.PENDING;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "read_at") private Instant readAt;
    @Column(name = "failed_at") private Instant failedAt;
    @Column(name = "failure_reason", length = 1000) private String failureReason;
    @Column(name = "failure_code", length = 128) private String failureCode;
    @Column(name = "retry_of_message_id") private Long retryOfMessageId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "referral_source_type", length = 64) private String referralSourceType;
    @Column(name = "referral_source_id", length = 191) private String referralSourceId;
    @Column(name = "referral_source_url", length = 1024) private String referralSourceUrl;
    @Column(name = "referral_headline", length = 1024) private String referralHeadline;
    @Column(name = "referral_body", length = 4096) private String referralBody;
    @Column(name = "referral_image_url", length = 1024) private String referralImageUrl;
    @Column(name = "referral_thumbnail_url", length = 1024) private String referralThumbnailUrl;
    @Column(name = "referral_welcome_text", length = 4096) private String referralWelcomeText;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = Instant.now(); }
}
