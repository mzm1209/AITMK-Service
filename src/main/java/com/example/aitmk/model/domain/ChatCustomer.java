package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ChatCustomer {

    private String customerId;
    private String lastMessage;
    private Instant lastMessageAt;
}
