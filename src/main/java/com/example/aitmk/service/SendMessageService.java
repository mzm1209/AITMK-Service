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

    /**
     * 发送视频消息。
     */
    void sendVideoMessage(String from, String to, String videoUrl, String caption);

    /**
     * 发送音频消息。
     */
    void sendAudioMessage(String from, String to, String audioUrl);

    /**
     * 发送文件消息。
     */
    void sendDocumentMessage(String from, String to, String documentUrl, String filename, String caption);
}
