package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentPresence;
import com.example.aitmk.service.AgentPresenceService;
import com.example.aitmk.service.v2.RealtimeEventService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentPresenceService 的默认实现。
 * <p>
 * 为每个坐席维护单一状态源，提供线程安全的状态变更、分配候选池管理、自动离线扫描与实时事件推送。
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class AgentPresenceServiceImpl implements AgentPresenceService {

    /** agentRowId → AgentPresence */
    private final Map<String, AgentPresence> statuses = new ConcurrentHashMap<>();

    /** agentRowId → loginRecordRowId (CRM 登录记录 ID) */
    private final Map<String, String> loginRecords = new ConcurrentHashMap<>();

    /** agentRowId → epoch millis (最近一次心跳/活动时间) */
    private final Map<String, Long> lastActiveAt = new ConcurrentHashMap<>();

    /** sessionId → agentRowId (WebSocket 会话映射) */
    private final Map<String, String> wsSessionToAgent = new ConcurrentHashMap<>();

    /** agentRowId → WS 连接计数 */
    private final Map<String, Integer> wsConnectionCount = new ConcurrentHashMap<>();

    /**
     * 分配候选池：仅包含 ONLINE 状态的坐席，保持插入顺序用于轮询。
     * 使用 synchronized 块保护并发读写。
     */
    private final Set<String> assignableAgents = Collections.synchronizedSet(new LinkedHashSet<>());

    private final RealtimeEventService events;

    private final com.example.aitmk.repository.AssignmentRecordRepository assignmentRepo;
    private final com.example.aitmk.repository.ResourceRepository resourceRepo;

    // ==================== 公有方法 ====================

    /**
     * Restore agent ONLINE presence from existing SERVING assignment records
     * after server restart. Without this, assignableAgents is empty and no
     * agent can receive new customers. Also repairs inconsistent resources
     * where PENDING_ASSIGNMENT resource has a SERVING assignment.
     */
    @PostConstruct
    public void restorePresenceFromDb() {
        // Restore ONLINE status for agents with active SERVING assignments
        // after server restart. Without this, the in-memory assignableAgents
        // set is empty and no agent can be assigned new customers.
        var serving = assignmentRepo.findByStatus(
                com.example.aitmk.model.entity.PersistenceEnums.AssignmentStatus.SERVING);
        for (var ar : serving) {
            String agentId = ar.getAgentId();
            if (agentId != null && !statuses.containsKey(agentId)) {
                statuses.put(agentId, AgentPresence.ONLINE);
                assignableAgents.add(agentId);
                log.info("Restored agent presence from SERVING assignment. agent={}", agentId);
            }
        }
        // Also fix inconsistent resources: PENDING with SERVING assignment
        for (var ar : serving) {
            Long resourceId = ar.getResourceId();
            if (resourceId != null) {
                resourceRepo.findById(resourceId).ifPresent(r -> {
                    if (r.getResourceStatus() == com.example.aitmk.model.entity.PersistenceEnums.ResourceStatus.PENDING_ASSIGNMENT) {
                        r.setResourceStatus(com.example.aitmk.model.entity.PersistenceEnums.ResourceStatus.ASSIGNED);
                        r.setAssignedAgentId(ar.getAgentId());
                        r.setAssignedAt(ar.getCreatedAt());
                        resourceRepo.save(r);
                        log.warn("Repaired inconsistent resource: id={}, phone={}, agent={}", 
                                r.getId(), r.getCustomerPhone(), ar.getAgentId());
                    }
                });
            }
        }
    }

    @Override
    public void onLogin(String agentRowId, String loginRecordRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        statuses.putIfAbsent(agentRowId, AgentPresence.AWAY);
        if (loginRecordRowId != null && !loginRecordRowId.isBlank()) {
            loginRecords.put(agentRowId, loginRecordRowId);
        }
        touch(agentRowId);
        log.info("Agent logged in. agent={}, presence={}, loginRecordRowId={}",
                agentRowId, AgentPresence.AWAY, loginRecordRowId);
    }

    @Override
    public AgentPresence onLoginDefault(String agentRowId, String loginRecordRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return null;
        }
        AgentPresence previous = statuses.put(agentRowId, AgentPresence.AWAY);
        // 不加入 assignableAgents（AWAY 状态默认不参与分配）
        if (loginRecordRowId != null && !loginRecordRowId.isBlank()) {
            loginRecords.put(agentRowId, loginRecordRowId);
        }
        touch(agentRowId);
        return previous;
    }

    @Override
    public AgentPresence changeStatus(String agentRowId, AgentPresence targetStatus) {
        if (agentRowId == null || agentRowId.isBlank() || targetStatus == null) {
            return null;
        }
        AgentPresence previous = statuses.put(agentRowId, targetStatus);
        if (targetStatus == AgentPresence.ONLINE) {
            assignableAgents.add(agentRowId);
        } else {
            assignableAgents.remove(agentRowId);
        }
        touch(agentRowId);
        publishStatusChanged(agentRowId, previous, targetStatus);
        log.info("Agent status changed. agent={}, from={} to={}", agentRowId, previous, targetStatus);
        return previous;
    }

    @Override
    public AgentPresence onLogout(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return null;
        }
        AgentPresence previous = statuses.remove(agentRowId);
        assignableAgents.remove(agentRowId);
        loginRecords.remove(agentRowId);
        lastActiveAt.remove(agentRowId);
        // 清理该坐席的 WS 映射
        wsSessionToAgent.entrySet().removeIf(e -> agentRowId.equals(e.getValue()));
        wsConnectionCount.remove(agentRowId);
        log.info("Agent logged out. agent={}, previousPresence={}", agentRowId, previous);
        return previous;
    }

    @Override
    public void touch(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        lastActiveAt.put(agentRowId, System.currentTimeMillis());
    }

    @Override
    public void onWebSocketSubscribe(String sessionId, String agentRowId) {
        if (sessionId == null || sessionId.isBlank() || agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        wsSessionToAgent.put(sessionId, agentRowId);
        wsConnectionCount.merge(agentRowId, 1, Integer::sum);
        touch(agentRowId);
        log.debug("WebSocket subscribed for agent. sessionId={}, agent={}", sessionId, agentRowId);
    }

    @Override
    public void onWebSocketDisconnect(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String agentRowId = wsSessionToAgent.remove(sessionId);
        if (agentRowId == null) {
            return;
        }
        wsConnectionCount.computeIfPresent(agentRowId, (k, v) -> {
            int updated = v - 1;
            return updated <= 0 ? null : updated;
        });
        log.debug("WebSocket disconnected. sessionId={}, agent={}", sessionId, agentRowId);
    }

    @Override
    public AgentPresence currentStatus(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return null;
        }
        return statuses.get(agentRowId);
    }

    @Override
    public Set<String> assignableAgents() {
        synchronized (assignableAgents) {
            return new LinkedHashSet<>(assignableAgents);
        }
    }

    @Override
    public Set<String> activeAgents() {
        Set<String> active = new LinkedHashSet<>();
        for (Map.Entry<String, AgentPresence> entry : statuses.entrySet()) {
            if (entry.getValue() == AgentPresence.ONLINE || entry.getValue() == AgentPresence.AWAY) {
                active.add(entry.getKey());
            }
        }
        return active;
    }

    @Override
    public List<AutoOfflineResult> scanInactive(int inactiveMinutes) {
        long threshold = inactiveMinutes * 60_000L;
        long now = System.currentTimeMillis();
        List<AutoOfflineResult> results = new ArrayList<>();

        // 遍历快照避免 ConcurrentModificationException
        List<Map.Entry<String, Long>> entries = new ArrayList<>(lastActiveAt.entrySet());
        for (Map.Entry<String, Long> entry : entries) {
            String agentId = entry.getKey();
            AgentPresence current = statuses.get(agentId);
            if (current == null || current == AgentPresence.OFFLINE) {
                // 已经离线或在 statuses 中不存在，不重复处理
                continue;
            }

            // 如果有活跃 WS 连接，跳过
            Integer wsCount = wsConnectionCount.get(agentId);
            if (wsCount != null && wsCount > 0) {
                continue;
            }

            // 如果上一次心跳在阈值内，跳过
            if (now - entry.getValue() < threshold) {
                continue;
            }

            // 触发状态变更（自动离线）
            String oldStatus = current.name();
            changeStatus(agentId, AgentPresence.OFFLINE);
            String loginRecordRowId = loginRecords.remove(agentId);

            results.add(new AutoOfflineResult(agentId, loginRecordRowId, oldStatus));
        }

        return results;
    }

    @Override
    public String getLoginRecordRowId(String agentRowId) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return null;
        }
        return loginRecords.get(agentRowId);
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 推送 AGENT_STATUS_CHANGED 实时事件给坐席本人。
     * <p>
     * 前端通过 /user/queue/events 订阅接收此事件。
     */
    private void publishStatusChanged(String agentRowId, AgentPresence previous, AgentPresence current) {
        var payload = Map.of(
                "agentId", agentRowId,
                "previousStatus", previous == null ? "NONE" : previous.name(),
                "currentStatus", current.name(),
                "changedAt", Instant.now().toString()
        );

        // 推送给坐席本人
        events.append("AGENT_STATUS_CHANGED", "AGENT", 0L, null, null, agentRowId, null, payload);
    }
}
