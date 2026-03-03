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

}