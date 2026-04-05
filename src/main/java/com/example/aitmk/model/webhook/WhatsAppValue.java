package com.example.aitmk.model.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WhatsAppValue {

    private String messaging_product;
    private Metadata metadata;
    private List<Contact> contacts;
    private List<Message> messages;
    private List<Status> statuses;

    @Data
    public static class Metadata {
        private String display_phone_number;
        private String phone_number_id;
    }

    @Data
    public static class Contact {
        private Profile profile;
        private String wa_id;
        private String identity_key_hash;
    }

    @Data
    public static class Profile {
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Status {
        private String id;
        private String status;
        private String timestamp;
        private String recipient_id;
        private String conversation;
        private String pricing;
    }
}
