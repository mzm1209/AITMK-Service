package com.example.aitmk.service;

/**
 * WhatsApp 消息发送服务
 */
public interface SendMessageService {

    /**
     * 发送文本消息
     *
     * @param to      用户手机号（含国家码）
     * @param message 消息内容
     */
    void sendTextMessage(String from ,String to, String message);

    /**
     * 发送图片消息
     *
     * @param to      用户手机号（含国家码）
     * @param imageUrl 图片 URL
     * @param caption 图片说明，可为空
     */
    void sendImageMessage(String from ,String to, String imageUrl, String caption);

    // 可扩展：视频、文档、模板消息等
}