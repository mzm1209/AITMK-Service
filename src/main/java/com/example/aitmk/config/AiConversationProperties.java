package com.example.aitmk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "aitmk.ai.conversation")
public class AiConversationProperties {
    private boolean enabled = false;
    private boolean autoAnalysisEnabled = false;
    private int autoAnalysisDebounceSeconds = 1800;
    private int autoAnalysisMinCustomerMessages = 5;
    private Instant autoAnalysisEnabledAt;
    private String timezone = "Asia/Jakarta";
    private String outputLocale = "zh-CN";
    private int maxMessages = 100;
    private Dify dify = new Dify();

    @Getter @Setter
    public static class Dify {
        private String baseUrl = "";
        private int timeoutSeconds = 90;
        private String userPrefix = "aitmk-conversation";
        private Workflow insight = new Workflow();
        private Workflow leadEnrichment = new Workflow();
        private Workflow replySuggestion = new Workflow();
        private Workflow followUpDraft = new Workflow();
        private Workflow appointmentDraft = new Workflow();
    }

    @Getter @Setter
    public static class Workflow {
        private boolean enabled = true;
        private String apiKey = "";
        private String workflowId = "";
    }
}
