package com.example.aitmk.service;

import com.example.aitmk.model.entity.PersistenceEnums.SentStatus;
import com.example.aitmk.model.entity.PersistenceEnums.SenderType;
import com.example.aitmk.model.entity.PersistenceEnums.MessageType;
import java.time.Instant;

public interface MessagePersistenceService {
    enum IncomingResult { CREATED, DUPLICATE }
    boolean existsExternalMessage(String externalMessageId);
    IncomingResult recordIncoming(String customerPhone, String businessAccountId, String externalMessageId,
                        String messageType, String content, String mediaId, String mediaUrl,
                        String mimeType, String rawPayload, Instant receivedAt,
                        String referralSourceType, String referralSourceId, String referralSourceUrl,
                        String referralHeadline, String referralBody, String referralImageUrl,
                        String referralThumbnailUrl, String referralWelcomeText);
    void updateDeliveryStatus(String externalMessageId, SentStatus status, Instant occurredAt, String failureReason);
    long createOutgoing(String customerPhone, String businessAccountId, SenderType senderType, String senderId,
                        String operatorRole, MessageType messageType, String content, String mediaId, String mediaUrl, String mimeType);
    void markOutgoingSent(long localMessageId, String externalMessageId, Instant sentAt);
    void markOutgoingFailed(long localMessageId, String failureReason, Instant failedAt, String failureCode);
    default void markOutgoingFailed(long localMessageId, String failureReason, Instant failedAt) {
        markOutgoingFailed(localMessageId, failureReason, failedAt, null);
    }
}
