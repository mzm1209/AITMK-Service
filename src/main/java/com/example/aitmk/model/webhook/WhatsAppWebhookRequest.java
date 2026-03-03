package com.example.aitmk.model.webhook;

import lombok.Data;
import java.util.List;

@Data
public class WhatsAppWebhookRequest {

    private String object;
    private List<Entry> entry;

    @Data
    public static class Entry {
        private String id;
        private List<Change> changes;
    }

    @Data
    public static class Change {
        private WhatsAppValue value;
        private String field;
    }
}