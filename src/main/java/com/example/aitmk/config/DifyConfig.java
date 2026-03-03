package com.example.aitmk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai.dify")
public class DifyConfig {

    private String baseUrl;
    private String apiKey;
}