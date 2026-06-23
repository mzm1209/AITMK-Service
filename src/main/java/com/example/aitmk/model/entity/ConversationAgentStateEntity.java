package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity @Table(name="conversation_agent_state",
        uniqueConstraints=@UniqueConstraint(name="uq_conversation_agent_state", columnNames={"conversation_id","agent_id"}))
public class ConversationAgentStateEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="conversation_id",nullable=false) private Long conversationId;
    @Column(name="agent_id",nullable=false,length=64) private String agentId;
    @Column(name="unread_count",nullable=false) private long unreadCount;
    @Column(name="last_read_message_id") private Long lastReadMessageId;
    @Column(name="last_read_at") private Instant lastReadAt;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void create(){var n=Instant.now();if(createdAt==null)createdAt=n;updatedAt=n;}
    @PreUpdate void update(){updatedAt=Instant.now();}
}
