package com.example.aitmk.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Getter @Setter @Entity @Table(name="realtime_event")
public class RealtimeEventEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="event_id",nullable=false,unique=true,length=36) private String eventId;
    @Column(name="event_type",nullable=false,length=64) private String eventType;
    @Column(name="aggregate_type",nullable=false,length=32) private String aggregateType;
    @Column(name="aggregate_id",nullable=false) private Long aggregateId;
    @Column(name="resource_id") private Long resourceId;
    @Column(name="conversation_id") private Long conversationId;
    @Column(name="target_agent_id",nullable=false,length=64) private String targetAgentId;
    @Column(name="aggregate_version") private Long aggregateVersion;
    @Column(name="payload_json",nullable=false,columnDefinition="LONGTEXT") private String payloadJson;
    @Column(name="occurred_at",nullable=false) private Instant occurredAt;
    @Column(name="published_at") private Instant publishedAt;
    @Column(name="publish_attempts",nullable=false) private int publishAttempts;
    @PrePersist void create(){if(eventId==null)eventId=java.util.UUID.randomUUID().toString();if(occurredAt==null)occurredAt=Instant.now();}
}
