package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.AgentPushMessage;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.service.AgentPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 推送实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketAgentPushServiceImpl implements AgentPushService {

    private final SimpMessagingTemplate messagingTemplate;

    /** 推送失败缓存（按坐席分组）。 */
    private final Map<String, List<AgentPushMessage>> failedBuffer = new ConcurrentHashMap<>();

    @Override
    public void pushHistory(String agentRowId, String customerPhone, List<ChatMessageRecord> messages) {
        AgentPushMessage payload = AgentPushMessage.builder()
                .type("history")
                .agentRowId(agentRowId)
                .customerPhone(customerPhone)
                .messages(messages)
                .build();
        safeSend(agentRowId, payload);
    }

    @Override
    public void pushNewMessage(String agentRowId, String customerPhone, ChatMessageRecord message) {
        AgentPushMessage payload = AgentPushMessage.builder()
                .type("new_message")
                .agentRowId(agentRowId)
                .customerPhone(customerPhone)
                .messages(List.of(message))
                .build();
        safeSend(agentRowId, payload);
    }

    @Override
    public void resendFailed(String agentRowId) {
        List<AgentPushMessage> pending = failedBuffer.getOrDefault(agentRowId, List.of());
        if (pending.isEmpty()) {
            return;
        }

        List<AgentPushMessage> copy = new ArrayList<>(pending);
        failedBuffer.remove(agentRowId);
        copy.forEach(msg -> safeSend(agentRowId, msg));
    }

    private void safeSend(String agentRowId, AgentPushMessage payload) {
        try {
            messagingTemplate.convertAndSend("/topic/agent/" + agentRowId, payload);
        } catch (Exception e) {
            log.error("WebSocket push failed, buffer message. agent={}, type={}", agentRowId, payload.getType(), e);
            failedBuffer.compute(agentRowId, (k, v) -> {
                List<AgentPushMessage> list = v == null ? new ArrayList<>() : new ArrayList<>(v);
                list.add(payload);
                return list;
            });
        }
    }
}
