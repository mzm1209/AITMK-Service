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

    @Data
    public static class Metadata {
        private String display_phone_number;
        private String phone_number_id;
    }

    @Data
    public static class Contact {
        private Profile profile;
        private String wa_id;
    }

    @Data
    public static class Profile {
        private String name;
    }
}