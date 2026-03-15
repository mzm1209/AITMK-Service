package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 客户会话摘要：用于客户列表页展示最近一条消息与时间。
 */
@Data
@Builder
public class ChatCustomer {

    /** 客户唯一标识（当前使用手机号/WhatsApp ID）。 */
    private String customerId;
    /** 最近一条消息内容。 */
    private String lastMessage;
    /** 最近一条消息时间。 */
    private Instant lastMessageAt;
}
