package com.example.aitmk.parser;

import com.example.aitmk.model.domain.WhatsAppMessage;
import com.example.aitmk.model.webhook.Message;

public class WhatsAppMessageParser {

    public static WhatsAppMessage parse(Message msg) {

        WhatsAppMessage.WhatsAppMessageBuilder builder =
                WhatsAppMessage.builder()
                        .from(msg.getFrom())
                        .type(msg.getType());

        switch (msg.getType()) {

            case "text" ->
                    builder.text(msg.getText().getBody());

            case "image" ->
                    builder.mediaId(msg.getImage().getId())
                            .mediaUrl(msg.getImage().getUrl());

            case "location" ->
                    builder.latitude(msg.getLocation().getLatitude())
                            .longitude(msg.getLocation().getLongitude());

            case "button" ->
                    builder.text(msg.getButton().getText());

            case "interactive" -> {
                if (msg.getInteractive().getButton_reply() != null) {
                    builder.text(msg.getInteractive()
                            .getButton_reply().getTitle());
                }
            }
        }

        return builder.build();
    }
}