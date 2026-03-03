package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ChatMessageRecord {

    private String customerId;
    private String sender;
    private String message;
    private Instant timestamp;
}
