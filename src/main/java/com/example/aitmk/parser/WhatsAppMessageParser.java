package com.example.aitmk.parser;

import com.example.aitmk.model.domain.WhatsAppMessage;
import com.example.aitmk.model.webhook.Message;

public class WhatsAppMessageParser {

    public static WhatsAppMessage parse(Message msg) {
        if (msg == null) {
            return WhatsAppMessage.builder().build();
        }

        WhatsAppMessage.WhatsAppMessageBuilder builder =
                WhatsAppMessage.builder()
                        .from(msg.getFrom())
                        .type(msg.getType());

        String type = msg.getType() == null ? "" : msg.getType();
        switch (type) {

            case "text" -> {
                if (msg.getText() != null) {
                    builder.text(msg.getText().getBody());
                }
            }

            case "image" -> fillMedia(builder, msg.getImage());
            case "audio" -> fillMedia(builder, msg.getAudio());
            case "video" -> fillMedia(builder, msg.getVideo());
            case "document" -> fillMedia(builder, msg.getDocument());

            case "location" -> {
                if (msg.getLocation() != null) {
                    builder.latitude(msg.getLocation().getLatitude())
                            .longitude(msg.getLocation().getLongitude());
                }
            }

            case "button" -> {
                if (msg.getButton() != null) {
                    builder.text(msg.getButton().getText());
                }
            }

            case "interactive" -> {
                if (msg.getInteractive() != null && msg.getInteractive().getButton_reply() != null) {
                    builder.text(msg.getInteractive()
                            .getButton_reply().getTitle());
                } else if (msg.getInteractive() != null && msg.getInteractive().getList_reply() != null) {
                    builder.text(msg.getInteractive()
                            .getList_reply().getTitle());
                }
            }
        }

        if (msg.getContext() != null) {
            builder.contextFrom(msg.getContext().getFrom())
                    .contextMessageId(msg.getContext().getId())
                    .forwarded(msg.getContext().getForwarded())
                    .frequentlyForwarded(msg.getContext().getFrequently_forwarded());
        }
        if (msg.getReferral() != null) {
            builder.referralSourceType(msg.getReferral().getSource_type())
                    .referralSourceId(msg.getReferral().getSource_id())
                    .referralHeadline(msg.getReferral().getHeadline())
                    .referralBody(msg.getReferral().getBody())
                    .referralCtaClid(msg.getReferral().getCtwa_clid());
        }

        return builder.build();
    }

    private static void fillMedia(WhatsAppMessage.WhatsAppMessageBuilder builder, Message.Media media) {
        if (media == null) {
            return;
        }
        builder.mediaId(media.getId())
                .mediaUrl(media.getUrl());
    }
}
