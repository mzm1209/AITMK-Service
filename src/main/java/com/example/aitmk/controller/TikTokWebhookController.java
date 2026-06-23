package com.example.aitmk.controller;

import com.example.aitmk.model.domain.InboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * TikTok 回调骨架。
 * 当前仅做基础的回调接收与日志，不实现具体的消息收发。
 */
@Slf4j
@RestController
@RequestMapping("/webhook/tiktok")
@RequiredArgsConstructor
public class TikTokWebhookController {

    private final ObjectMapper objectMapper;

    @PostMapping
    public String handleWebhook(@RequestBody String rawPayload) {
        log.info("TikTok webhook received. payload={}", rawPayload);
        // TODO: 解析 TikTok 回调，转换为 InboundMessage，调 InboundGateway
        return "ok";
    }

    @GetMapping
    public String verify(@RequestParam("hub.challenge") String challenge) {
        log.info("TikTok webhook verification. challenge={}", challenge);
        return challenge;
    }
}