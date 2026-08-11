package com.example.aitmk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aitmk.ai.dify")
public class AiDifyWorkflowProperties {
    private boolean enabled = true;
    private String baseUrl = "";
    private String apiKey = "";
    private int timeoutSeconds = 900;
    private String dailyReportUser = "aitmk-ai-daily-report";
}
