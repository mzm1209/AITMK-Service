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
    /** 广告来源信息（Click to WhatsApp）。 */
    private ReferralInfo referral;

    @Data
    @Builder
    public static class ReferralInfo {
        private String sourceUrl;
        private String sourceId;
        private String sourceType;
        private String body;
        private String headline;
        private String mediaType;
        private String imageUrl;
        private String videoUrl;
        private String thumbnailUrl;
        private String ctwaClid;
        private String welcomeText;
    }
}
