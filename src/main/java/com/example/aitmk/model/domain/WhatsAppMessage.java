package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WhatsAppMessage {

    private String from;
    private String type;
    private String text;
    private String mediaId;
    private String mediaUrl;
    private Double latitude;
    private Double longitude;
}