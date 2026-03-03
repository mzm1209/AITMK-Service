package com.example.aitmk.service.impl;

import com.example.aitmk.config.DifyConfig;
import com.example.aitmk.service.AiService;
import com.example.aitmk.service.AiStreamCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DifyAiServiceImpl implements AiService {

    private final DifyConfig config;

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public String chat(String query) {

        String url = config.getBaseUrl() + "/v1/chat-messages";

        Map<String, Object> body = buildRequest(query, false);

        return webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(); // 同步返回
    }

    @Override
    public void chatStream(String query, AiStreamCallback callback) {

        String url = config.getBaseUrl() + "/v1/chat-messages";

        Map<String, Object> body = buildRequest(query, true);

        Flux<String> flux = webClient.post()
                .uri(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);

        flux.subscribe(
                callback::onMessage,
                callback::onError,
                callback::onComplete
        );
    }

    private Map<String, Object> buildRequest(String query, boolean streaming) {

        Map<String, Object> body = new HashMap<>();
        body.put("inputs", new HashMap<>());
        body.put("query", query);
        body.put("response_mode", streaming ? "streaming" : "blocking");
        body.put("conversation_id", "");
        body.put("user", "whatsapp-user");

        return body;
    }
}