package com.example.aitmk.model.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Message {

    private String from;
    private String id;
    private String timestamp;
    private String type;

    private Text text;
    private Media image;
    private Media audio;
    private Media video;
    private Media document;
    private Location location;
    private Button button;
    private Interactive interactive;

    @Data
    public static class Text {
        private String body;
    }

    @Data
    public static class Media {
        private String id;
        private String mime_type;
        private String sha256;
        private String url;
    }

    @Data
    public static class Location {
        private Double latitude;
        private Double longitude;
        private String name;
        private String address;
    }

    @Data
    public static class Button {
        private String text;
        private String payload;
    }

    @Data
    public static class Interactive {
        private String type;
        private Reply button_reply;
        private Reply list_reply;

        @Data
        public static class Reply {
            private String id;
            private String title;
            private String description;
        }
    }
}