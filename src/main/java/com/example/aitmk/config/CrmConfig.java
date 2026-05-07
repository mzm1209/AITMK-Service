package com.example.aitmk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * CRM OpenAPI 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "crm")
public class CrmConfig {

    private String baseUrl;
    private String appKey;
    private String sign;
    private String ownerId;
    private Clue clue = new Clue();

    @Data
    public static class Clue {
        private String appKey;
        private String sign;
    }
}
