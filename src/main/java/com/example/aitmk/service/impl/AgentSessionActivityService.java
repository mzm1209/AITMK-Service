package com.example.aitmk.service.impl;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跟踪坐席最近一次Web活跃时间与登录记录ID，用于无操作自动离线。
 */
@Service
public class AgentSessionActivityService {

    private final Map<String, String> loginRecordByAgent = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActiveAtMillis = new ConcurrentHashMap<>();
    private final Map<String, String> wsSessionToAgent = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> wsSessionCountByAgent = new ConcurrentHashMap<>();

    public void onLogin(String agentRowId, String loginRecordRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        if (loginRecordRowId != null && !loginRecordRowId.isBlank()) {
            loginRecordByAgent.put(agentRowId, loginRecordRowId);
        }
        touch(agentRowId);
    }

    public void touch(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        lastActiveAtMillis.put(agentRowId, System.currentTimeMillis());
    }

    public String onLogout(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return null;
        }
        lastActiveAtMillis.remove(agentRowId);
        wsSessionCountByAgent.remove(agentRowId);
        wsSessionToAgent.entrySet().removeIf(e -> agentRowId.equals(e.getValue()));
        return loginRecordByAgent.remove(agentRowId);
    }

    /**
     * WebSocket 订阅建立后记录会话与坐席映射，作为“活跃在线”信号。
     */
    public void onWebSocketSubscribe(String sessionId, String agentRowId) {
        if (sessionId == null || sessionId.isBlank() || agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        wsSessionToAgent.put(sessionId, agentRowId);
        wsSessionCountByAgent.computeIfAbsent(agentRowId, k -> new AtomicInteger()).incrementAndGet();
        touch(agentRowId);
    }

    /**
     * WebSocket 断开时减少对应坐席连接计数。
     */
    public void onWebSocketDisconnect(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String agentRowId = wsSessionToAgent.remove(sessionId);
        if (agentRowId == null) {
            return;
        }
        AtomicInteger counter = wsSessionCountByAgent.get(agentRowId);
        if (counter == null) {
            return;
        }
        if (counter.decrementAndGet() <= 0) {
            wsSessionCountByAgent.remove(agentRowId);
        }
    }

    public List<AgentSessionState> scanInactive(int inactiveMinutes) {
        long threshold = Math.max(1, inactiveMinutes) * 60_000L;
        long now = System.currentTimeMillis();
        List<AgentSessionState> inactive = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastActiveAtMillis.entrySet()) {
            AtomicInteger wsCounter = wsSessionCountByAgent.get(entry.getKey());
            if (wsCounter != null && wsCounter.get() > 0) {
                // 存在有效 WebSocket 连接时视为活跃，不自动离线。
                continue;
            }
            if (now - entry.getValue() < threshold) {
                continue;
            }
            String agentRowId = entry.getKey();
            lastActiveAtMillis.remove(agentRowId);
            String loginRowId = loginRecordByAgent.remove(agentRowId);
            inactive.add(new AgentSessionState(agentRowId, loginRowId));
        }
        return inactive;
    }

    public record AgentSessionState(String agentRowId, String loginRecordRowId) {}
}
