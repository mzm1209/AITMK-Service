package com.example.aitmk.model.api.v2;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    public record ConversationFilterOption(String value, String label) {}
    public record ConversationFilterOptions(List<ConversationFilterOption> leadTypes,
                                            List<ConversationFilterOption> leadStatuses) {}

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
            Instant sentAt, Instant deliveredAt, Instant readAt, ReferralView referral) {}
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
    public record ReferralView(
            String sourceType, String sourceId, String sourceUrl,
            String headline, String body, String imageUrl, String thumbnailUrl, String welcomeText) {}
    public record SendMessageResult(MessageView message) {}
    public record MediaUploadResult(String mediaId, String fileName, String mimeType, String mediaType) {}
    public record DashboardSummary(long activeConversations, long pendingAssignments, long unreadConversations,
            long expiringReplyWindows, long todayReceived, long todayClosed,
            double firstHumanResponseP50Seconds, double firstHumanResponseP90Seconds) {}
    public record DashboardAnalytics(
            String scope, String granularity, String from, String to,
            DashboardAnalyticsCards cards,
            List<LeadTrendPoint> leadTrend,
            List<ResponseTrendPoint> responseTrend,
            List<DashboardAgentStats> agentStats) {}
    public record DashboardAnalyticsCards(
            long leadCount,
            Long firstResponseAvgSeconds,
            Long firstResponseP50Seconds,
            Long firstResponseP90Seconds,
            Long averageResponseSeconds,
            Long averageResponseP90Seconds,
            long activeConversations,
            long resolvedConversations,
            double averageResolvedConversations) {}
    public record LeadTrendPoint(String bucket, long leadCount) {}
    public record ResponseTrendPoint(String bucket, Long firstResponseAvgSeconds, Long averageResponseSeconds) {}
    public record DashboardAgentStats(
            String agentId,
            String agentName,
            long leadCount,
            Long firstResponseAvgSeconds,
            Long firstResponseP90Seconds,
            Long averageResponseSeconds,
            Long averageResponseP90Seconds,
            long activeConversations,
            long resolvedConversations) {}

    // ── AI Daily Reports ──
    public record AiDailyReportGenerateRequest(String reportDate, String generationType, String scope, Boolean force) {}
    public record AiDailyReportListView(List<AiDailyReportSummaryView> items) {}
    public record AiDailyReportSummaryView(
            String id,
            String reportDate,
            int version,
            String status,
            String generationType,
            String scope,
            String executiveSummary,
            String riskLevel,
            Integer businessHealthScore,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt) {}
    public record AiDailyReportView(
            String id,
            String reportDate,
            int version,
            String status,
            String generationType,
            String scope,
            String snapshotJson,
            String aiResultJson,
            String executiveSummary,
            String riskLevel,
            Integer businessHealthScore,
            String difyRunId,
            String errorMessage,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt,
            List<AiDailyReportConversationView> conversations) {}
    public record AiDailyReportConversationView(
            String id,
            String reportId,
            String conversationId,
            String customerPhone,
            String agentId,
            String agentName,
            Integer messageCount,
            Integer customerMessageCount,
            Integer agentMessageCount,
            Integer priorityScore,
            String appointmentStatus,
            String resolvedStatus,
            Integer timeoutCount,
            String conversationSnapshotJson,
            String aiResultJson,
            Instant createdAt,
            Instant updatedAt) {}
    public record ReadRequest(String lastReadMessageId) {}
    public record ReadResult(String conversationId, String agentId, String lastReadMessageId, Instant lastReadAt, long unreadCount) {}
    public record TransferRequest(String targetAgentId, String reason, long expectedVersion) {}
    public record CloseRequest(String reasonCode, String remark, long expectedVersion) {}
    public record EventView(String eventId, String eventType, Instant occurredAt, Long aggregateVersion,
                            String resourceId, String conversationId, Object payload) {}
    public record UnreadCountPayload(long unreadCount, String lastReadMessageId) {}
    public record AssignmentChangedPayload(String fromAgentId, String targetAgentId, String reason) {}
    public record AgentStats(
        String agentId,
        long totalLeadCount,
        long activeConversations,
        long todayClosed,
        Double firstResponseP50,
        Double firstResponseP90,
        Double averageResponseTime,
        long totalServed
    ) {}

    // ── Agent Quick Replies ──
    public record QuickReplyRequest(String title, String content, String category, Integer sortOrder) {}
    public record QuickReplyView(
            String id,
            String title,
            String content,
            String category,
            int sortOrder,
            Instant updatedAt) {}
    public record QuickReplyListView(List<QuickReplyView> items) {}

    // ── CRM Profile / Link Lead ──
    public record CrmProfileView(
            String resourceId, String customerPhone, String customerName,
            boolean linked, String rowId,
            Object clue, Object fieldsConfig) {}
    public record LinkLeadRequest(String rowId) {}

    // ── Appointments ──
    public record CreateAppointmentRequest(
            Long resourceId,
            String leadRowId,
            String followUpRowId,
            String contactNumber,
            String studentName,
            String grade,
            String school,
            String parentName,
            String programInterest,
            String appointmentDate,
            String appointmentInfo,
            Object center,
            String appointmentStatus,
            String followUpStatus,
            String followUpDueAt,
            String assignedTime,
            String visitStatus,
            Integer interestLevel,
            String leadsChannel,
            String intern,
            Boolean triggerWorkflow) {}
    public record AppointmentView(
            String rowId,
            String appointmentId,
            String contactNumber,
            String studentName,
            String parentName,
            String appointmentDate,
            String appointmentInfo,
            String appointmentStatus,
            String visitStatus,
            String followUpStatus,
            String followUpDueAt,
            String centerName,
            String staffName,
            String leadRowId,
            String followUpRowId,
            Map<String, Object> raw) {}
    public record AppointmentListView(List<AppointmentView> rows, int total) {}

    // ── Follow-ups ──
    public record CreateFollowUpRequest(
            Long resourceId,
            String leadRowId,
            String type,
            String summary,
            String details,
            String reminderAt,
            Object center,
            Boolean triggerWorkflow) {}
    public record FollowUpView(
            String rowId,
            String type,
            String summary,
            String details,
            String reminderAt,
            String createdAt,
            String staffName,
            String centerName,
            Map<String, Object> raw) {}
    public record FollowUpListView(List<FollowUpView> rows, int total) {}

    // ── Worksheet Fields ──
    public record WorksheetFieldsView(String worksheetId, String worksheetName, List<FieldConfigView> fields) {}
    public record FieldConfigView(String controlId, String controlName, int dataType, List<FieldOption> options) {}
    public record FieldOption(String key, String value) {}
}
