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
    public void sendTextMessage(String from ,String to, String message) {

        String url = config.getGraphUrl() + "/" + from + "/messages";

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "text");

        Map<String, String> text = new HashMap<>();
        text.put("body", message);

        body.put("text", text);

        webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(resp -> log.info("WhatsApp sendTextMessage response: {}", resp))
                .doOnError(err -> log.error("WhatsApp sendTextMessage error", err))
                .subscribe(); // 异步发送
    }

    @Override
    public void sendImageMessage(String from ,String to, String imageUrl, String caption) {

        String url = config.getGraphUrl() + "/" + from + "/messages";

        Map<String, Object> body = new HashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "image");

        Map<String, String> image = new HashMap<>();
        image.put("link", imageUrl);
        if (caption != null) {
            image.put("caption", caption);
        }

        body.put("image", image);

        webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.getAccessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .doOnNext(resp -> log.info("WhatsApp sendImageMessage response: {}", resp))
                .doOnError(err -> log.error("WhatsApp sendImageMessage error", err))
                .subscribe(); // 异步发送
    }
}