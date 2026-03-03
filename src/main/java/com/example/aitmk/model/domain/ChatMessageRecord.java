package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 聊天消息记录：统一表示客户、AI 与人工三类消息。
 */
@Data
@Builder
public class ChatMessageRecord {

    /** 客户 ID。 */
    private String customerId;
    /** 发送方类型：customer / ai / agent。 */
    private String sender;
    /** 消息正文。 */
    private String message;
    /** 消息时间戳。 */
    private Instant timestamp;
}
