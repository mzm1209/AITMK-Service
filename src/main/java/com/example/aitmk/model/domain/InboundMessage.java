package com.example.aitmk.model.domain;

/**
 * 统一入站消息模型，用于多渠道（WhatsApp/TikTok 等）消息处理。
 */
public record InboundMessage(
    String channel,
    String externalUserId,
    String customerPhone,
    String customerName,
    String messageId,
    String messageType,
    String content,
    String campaignId,
    String rawPayload
) {}