package com.example.aitmk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppConfig {

    private String accessToken;
    private String graphUrl;
    private String verifyToken;
    private String appSecret;

    /**
     * When false, outgoing messages skip the provider API and are marked as sent immediately.
     * Defaults to true for production safety. Set false when using WebhookManualSimulator or test environments.
     */
    private boolean outboundEnabled = true;

}