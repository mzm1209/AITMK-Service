package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 坐席分配服务（内存版）：
 * 1) 记录在线坐席
 * 2) 记录客户-坐席绑定
 * 3) 按登录顺序轮询分配
 */
@Service
public class InMemoryAgentDispatchService implements AgentDispatchService {

    private final Set<String> onlineAgents = new LinkedHashSet<>();
    private final Map<String, String> customerAgentMap = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    @Override
    public synchronized void markOnline(String agentRowId) {
        onlineAgents.add(agentRowId);
    }

    @Override
    public synchronized boolean hasOnlineAgent() {
        return !onlineAgents.isEmpty();
    }

    @Override
    public Optional<String> getAssignedAgent(String customerPhone) {
        return Optional.ofNullable(customerAgentMap.get(customerPhone));
    }

    @Override
    public synchronized Optional<String> assignIfAbsent(String customerPhone) {
        String existing = customerAgentMap.get(customerPhone);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (onlineAgents.isEmpty()) {
            return Optional.empty();
        }

        ArrayList<String> queue = new ArrayList<>(onlineAgents);
        int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), queue.size());
        String selected = queue.get(idx);
        customerAgentMap.put(customerPhone, selected);
        return Optional.of(selected);
    }
}
