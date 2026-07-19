package com.example.aitmk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aitmk.ai.daily-report")
public class AiDailyReportProperties {
    private boolean enabled = true;
    private String cron = "0 0 10 * * ?";
    private String zone = "Asia/Jakarta";
    private long firstResponseSlaSeconds = 300;
    private long unrespondedTimeoutSeconds = 1800;
    private String reportStatus = "FINAL";
    private String workSchedule = "09:00-18:00";
    private int maxConversationCandidates = 5;
    private int maxMessagesPerConversation = 80;
}
