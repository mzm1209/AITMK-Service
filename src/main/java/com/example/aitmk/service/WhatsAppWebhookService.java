package com.example.aitmk.service;

public interface WhatsAppWebhookService {

    /**
     * 处理 webhook 原始 payload
     */
    void process(String payload);
}