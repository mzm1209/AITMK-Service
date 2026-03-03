package com.example.aitmk.controller;

import com.example.aitmk.config.WhatsAppConfig;
import com.example.aitmk.security.WebhookSignatureValidator;
import com.example.aitmk.service.WhatsAppWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WhatsAppWebhookController {

    private final WhatsAppConfig properties;
    private final WebhookSignatureValidator signatureValidator;
    private final WhatsAppWebhookService webhookService;

    /**
     * Meta 验证 webhook
     */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam("hub.mode") String mode,
            @RequestParam("hub.challenge") String challenge,
            @RequestParam("hub.verify_token") String token) {

        if ("subscribe".equals(mode)
                && properties.getVerifyToken().equals(token)) {
            return ResponseEntity.ok(challenge);
        }

        return ResponseEntity.status(403).build();
    }

    /**
     * 接收消息
     */
    @PostMapping
    public ResponseEntity<Void> receive(
            @RequestBody String payload,
            HttpServletRequest request) {

        // 验证签名
//        String signature = request.getHeader("X-Hub-Signature-256");
//        signatureValidator.validate(signature, payload);

        // 异步处理
        webhookService.process(payload);

        // 立即返回 200
        return ResponseEntity.ok().build();
    }
}