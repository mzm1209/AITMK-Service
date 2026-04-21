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
    private Context context;
    private Referral referral;

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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Context {
        /** 业务号码 / 来源号码 */
        private String from;
        /** 上下文消息ID */
        private String id;
        /** 转发标记 */
        private Boolean forwarded;
        /** 频繁转发标记 */
        private Boolean frequently_forwarded;
        private ReferredProduct referred_product;

        @Data
        public static class ReferredProduct {
            private String catalog_id;
            private String product_retailer_id;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Referral {
        private String source_url;
        private String source_id;
        private String source_type;
        private String body;
        private String headline;
        private String media_type;
        private String image_url;
        private String video_url;
        private String thumbnail_url;
        private String ctwa_clid;
        private WelcomeMessage welcome_message;

        @Data
        public static class WelcomeMessage {
            private String text;
        }
    }
}
