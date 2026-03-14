package com.example.aitmk.service.impl;

import com.example.aitmk.config.WhatsAppConfig;
import com.example.aitmk.service.SendMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SendMessageServiceImpl implements SendMessageService {

    private final WhatsAppConfig config;

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public void sendTextMessage(String from, String to, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "text");

        Map<String, String> text = new HashMap<>();
        text.put("body", message);
        body.put("text", text);

        postMessage(from, body, "sendTextMessage");
    }

    @Override
    public void sendImageMessage(String from, String to, String imageUrl, String caption) {
        Map<String, String> image = new HashMap<>();
        image.put("link", imageUrl);
        if (caption != null && !caption.isBlank()) {
            image.put("caption", caption);
        }
        sendMediaMessage(from, to, "image", image, "sendImageMessage");
    }

    @Override
    public void sendVideoMessage(String from, String to, String videoUrl, String caption) {
        Map<String, String> video = new HashMap<>();
        video.put("link", videoUrl);
        if (caption != null && !caption.isBlank()) {
            video.put("caption", caption);
        }
        sendMediaMessage(from, to, "video", video, "sendVideoMessage");
    }

    @Override
    public void sendAudioMessage(String from, String to, String audioUrl) {
        Map<String, String> audio = new HashMap<>();
        audio.put("link", audioUrl);
        sendMediaMessage(from, to, "audio", audio, "sendAudioMessage");
    }

    @Override
    public void sendDocumentMessage(String from, String to, String documentUrl, String filename, String caption) {
        Map<String, String> document = new HashMap<>();
        document.put("link", documentUrl);
        if (filename != null && !filename.isBlank()) {
            document.put("filename", filename);
        }
        if (caption != null && !caption.isBlank()) {
            document.put("caption", caption);
        }
        sendMediaMessage(from, to, "document", document, "sendDocumentMessage");
    }

    private void sendMediaMessage(String from, String to, String type, Map<String, String> mediaBody, String actionName) {
        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", type);
        body.put(type, mediaBody);

        postMessage(from, body, actionName);
    }

    private void postMessage(String from, Map<String, Object> body, String actionName) {
        String url = config.getGraphUrl() + "/" + from + "/messages";
        webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(resp -> log.info("WhatsApp {} response: {}", actionName, resp))
                .doOnError(err -> log.error("WhatsApp {} error", actionName, err))
                .subscribe();
    }
}
