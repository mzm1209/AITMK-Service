package com.example.aitmk.model.api.v2;

import java.time.Instant;
import java.util.List;

public final class V2Api {
    private V2Api() {}

    public record Response<T>(boolean success, T data, String requestId) {
        public static <T> Response<T> ok(T data) { return new Response<>(true, data, RequestIds.current()); }
    }
    public record Failure(boolean success, Error error, String requestId) {
        public Failure(Error error) { this(false, error, RequestIds.current()); }
    }
    public record Error(String code, String message, Object details) {}
    public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
    public record CustomerBrief(String phone, String name) {}
    public record AgentBrief(String agentId, String name) {}
    public record MediaView(String mediaId, String mediaUrl, String mimeType, String fileName) {}

    public record ConversationSummary(
            String conversationId, String resourceId, CustomerBrief customer, String channel, String status,
            String resourceStatus, String aiState, AgentBrief assignedAgent, boolean replyable,
            Instant replyDeadline, long unreadCount, String lastReadMessageId, MessageView lastMessage,
            Instant startedAt, Instant closedAt, String closeReason, long version) {}

    public record ConversationDetail(
            String conversationId, String resourceId, CustomerBrief customer, String channel, String status,
            String resourceStatus, String aiState, AgentBrief assignedAgent, boolean replyable,
            Instant replyDeadline, long unreadCount, String lastReadMessageId, MessageView lastMessage,
            Instant startedAt, Instant closedAt, String closeReason, long version, ResourceView resource) {
        public static ConversationDetail of(ConversationSummary c, ResourceView resource) {
            return new ConversationDetail(c.conversationId(), c.resourceId(), c.customer(), c.channel(), c.status(),
                    c.resourceStatus(), c.aiState(), c.assignedAgent(), c.replyable(), c.replyDeadline(),
                    c.unreadCount(), c.lastReadMessageId(), c.lastMessage(), c.startedAt(), c.closedAt(),
                    c.closeReason(), c.version(), resource);
        }
    }

    public record MessageView(
            String messageId, String conversationId, String resourceId, String externalMessageId,
            String clientRequestId, String senderType, String senderId, String messageType, String content,
            MediaView media, String sentStatus, String failureCode, String failureReason, Instant createdAt,
            Instant sentAt, Instant deliveredAt, Instant readAt) {}
    public record ResourceView(
            String resourceId, String customerPhone, String customerName, String sourceChannel,
            String resourceType, String resourceStatus, AgentBrief assignedAgent, MessageView lastMessage, Instant createdAt,
            Instant updatedAt, long version) {}
    public record AssignmentView(
            String assignmentId, String resourceId, String conversationId, AgentBrief agent,
            String assignedBy, String assignType, String status, boolean replyable, Instant assignedAt,
            Instant closedAt, String closeReason) {}
    public record ConversationHistoryView(String conversationId, String status, Instant startedAt, long version) {}
    public record MessageMediaRequest(String mediaId, String fileName, String mimeType) {}
    public record SendMessageRequest(String messageType, String content, MessageMediaRequest media, String retryOfMessageId) {}
    public record SendMessageResult(MessageView message) {}
    public record MediaUploadResult(String mediaId, String fileName, String mimeType, String mediaType) {}
    public record DashboardSummary(long activeConversations, long pendingAssignments, long unreadConversations,
            long expiringReplyWindows, long todayReceived, long todayClosed,
            double firstHumanResponseP50Seconds, double firstHumanResponseP90Seconds) {}
    public record ReadRequest(String lastReadMessageId) {}
    public record ReadResult(String conversationId, String agentId, String lastReadMessageId, Instant lastReadAt, long unreadCount) {}
    public record TransferRequest(String targetAgentId, String reason, long expectedVersion) {}
    public record CloseRequest(String reasonCode, String remark, long expectedVersion) {}
    public record EventView(String eventId, String eventType, Instant occurredAt, Long aggregateVersion,
                            String resourceId, String conversationId, Object payload) {}
    public record UnreadCountPayload(long unreadCount, String lastReadMessageId) {}
    public record AssignmentChangedPayload(String fromAgentId, String targetAgentId, String reason) {}
}
