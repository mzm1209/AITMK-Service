package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.AgentPushMessage;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.service.AgentPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * WebSocket 推送实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketAgentPushServiceImpl implements AgentPushService {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void pushHistory(String agentRowId, String customerPhone, List<ChatMessageRecord> messages) {
        AgentPushMessage payload = AgentPushMessage.builder()
                .type("history")
                .agentRowId(agentRowId)
                .customerPhone(customerPhone)
                .messages(messages)
                .build();
        messagingTemplate.convertAndSend("/topic/agent/" + agentRowId, payload);
    }

    @Override
    public void pushNewMessage(String agentRowId, String customerPhone, ChatMessageRecord message) {
        AgentPushMessage payload = AgentPushMessage.builder()
                .type("new_message")
                .agentRowId(agentRowId)
                .customerPhone(customerPhone)
                .messages(List.of(message))
                .build();
        messagingTemplate.convertAndSend("/topic/agent/" + agentRowId, payload);
        log.debug("Push new customer message to agent={}, customer={}", agentRowId, customerPhone);
    }
}
