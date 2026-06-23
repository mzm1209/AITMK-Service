package com.example.aitmk.model.entity;

public final class PersistenceEnums {
    private PersistenceEnums() {}

    public enum SourceChannel { META, TIKTOK, MANUAL, CRM }
    public enum ResourceType { NEW_LEAD, RECONSULTATION, APPOINTMENT, COMPLAINT, INVALID, OTHER }
    public enum ResourceStatus { PENDING_ASSIGNMENT, AI_SERVING, ASSIGNED, FOLLOWING_UP, APPOINTMENT, RESOLVED, INVALID, CLOSED }
    public enum ConversationStatus { ACTIVE, AI_ACTIVE, HUMAN_ACTIVE, CLOSED }
    public enum AiState { NONE, WELCOME_SENT, COLLECTING_INFO, WAITING_CENTER, TRANSFERRED }
    public enum SenderType { CUSTOMER, AI, AGENT, MANAGER, SYSTEM }
    public enum MessageType { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, LOCATION, INTERACTIVE, SYSTEM }
    public enum SentStatus { PENDING, SENT, DELIVERED, READ, FAILED }
    public enum AssignType { AUTO, MANUAL, TRANSFER }
    public enum AssignmentStatus { SERVING, CLOSED, TRANSFERRED }
}
