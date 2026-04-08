package com.example.aitmk.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * 监听 WebSocket 订阅/断开，增强坐席活跃状态判定稳定性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWebSocketPresenceListener {

    private static final String AGENT_TOPIC_PREFIX = "/topic/agent/";

    private final AgentSessionActivityService sessionActivityService;

    @EventListener
    public void onSessionSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(AGENT_TOPIC_PREFIX)) {
            return;
        }
        String agentRowId = destination.substring(AGENT_TOPIC_PREFIX.length());
        sessionActivityService.onWebSocketSubscribe(sessionId, agentRowId);
        log.info("WebSocket subscribed for agent topic. sessionId={}, agent={}", sessionId, agentRowId);
    }

    @EventListener
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        sessionActivityService.onWebSocketDisconnect(sessionId);
        log.info("WebSocket disconnected. sessionId={}", sessionId);
    }
}
