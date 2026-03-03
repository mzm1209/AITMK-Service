package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.WhatsAppMessage;
import com.example.aitmk.model.webhook.WhatsAppWebhookRequest;
import com.example.aitmk.parser.WhatsAppMessageParser;
import com.example.aitmk.service.WhatsAppWebhookService;
import com.example.aitmk.service.AiService;
import com.example.aitmk.service.SendMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import com.example.aitmk.util.AiReplyParser;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppWebhookServiceImpl implements WhatsAppWebhookService  {

    private final ObjectMapper objectMapper;

    private final AiService aiService;
    private final SendMessageService sendService;
    @Override
    @Async
    public void process(String payload) {

        try {

            WhatsAppWebhookRequest request =
                    objectMapper.readValue(payload,
                            WhatsAppWebhookRequest.class);

            if (request.getEntry() == null) {
                return;
            }

            request.getEntry().forEach(entry -> {

                if (entry.getChanges() == null) return;

                entry.getChanges().forEach(change -> {

                    if (change.getValue() == null ||
                            change.getValue().getMessages() == null) {
                        return;
                    }

                    change.getValue().getMessages().forEach(message -> {

                        WhatsAppMessage parsed =
                                WhatsAppMessageParser.parse(message);

                        log.info("Parsed Message: {}", parsed);

                        // TODO:
                        // 1. 存数据库
                        // 2. 推送 MQ
                        // 3. 调用 AI

                        // 阻塞获取 AI 回复 JSON
                        String aiReplyJson = aiService.chat(parsed.getText());

                        // 解析 answer 字段
                        String aiAnswer = AiReplyParser.parseAnswer(aiReplyJson);

                        // 打印日志，方便调试
                        log.info("aiReply Message: {}", aiAnswer);


                        // 4. 自动回复
                         sendService.sendTextMessage("1019964791197772",parsed.getFrom(), aiAnswer);

                    });
                });
            });

        } catch (Exception e) {
            log.error("Webhook processing error", e);
        }
    }
}