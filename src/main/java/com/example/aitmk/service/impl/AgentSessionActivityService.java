package com.example.aitmk.service.impl;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪坐席最近一次Web活跃时间与登录记录ID，用于无操作自动离线。
 */
@Service
public class AgentSessionActivityService {

    private final Map<String, String> loginRecordByAgent = new ConcurrentHashMap<>();
    private final Map<String, Long> lastActiveAtMillis = new ConcurrentHashMap<>();

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
        return loginRecordByAgent.remove(agentRowId);
    }

    public List<AgentSessionState> scanInactive(int inactiveMinutes) {
        long threshold = Math.max(1, inactiveMinutes) * 60_000L;
        long now = System.currentTimeMillis();
        List<AgentSessionState> inactive = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastActiveAtMillis.entrySet()) {
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

