package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class CrmChatRecord {

    private String businessAccountId;
    private String customerPhone;
    private String customerNickname;
    private String agentRowId;
    private String sender;
    private String content;
    private Instant sendTime;
}
