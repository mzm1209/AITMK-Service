package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import lombok.extern.slf4j.Slf4j;
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
 * 4) 维护未分配客户队列
 */
@Slf4j
@Service
public class InMemoryAgentDispatchService implements AgentDispatchService {

    private final Set<String> onlineAgents = new LinkedHashSet<>();
    private final Map<String, String> customerAgentMap = new ConcurrentHashMap<>();
    private final Set<String> pendingCustomers = new LinkedHashSet<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    @Override
    public synchronized void markOnline(String agentRowId) {
        onlineAgents.add(agentRowId);
        log.info("Agent online. agent={}, onlineCount={}", agentRowId, onlineAgents.size());
    }

    @Override
    public synchronized void markOffline(String agentRowId) {
        onlineAgents.remove(agentRowId);
        log.info("Agent offline. agent={}, onlineCount={}", agentRowId, onlineAgents.size());
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
            pendingCustomers.remove(customerPhone);
            return Optional.of(existing);
        }
        if (onlineAgents.isEmpty()) {
            pendingCustomers.add(customerPhone);
            log.info("No online agent, customer moved to pending queue. customer={}, pendingCount={}", customerPhone, pendingCustomers.size());
            return Optional.empty();
        }

        ArrayList<String> queue = new ArrayList<>(onlineAgents);
        int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), queue.size());
        String selected = queue.get(idx);
        customerAgentMap.put(customerPhone, selected);
        pendingCustomers.remove(customerPhone);
        log.info("Assigned customer to agent. customer={}, agent={}, assignedCount={}, pendingCount={}",
                customerPhone, selected, customerAgentMap.size(), pendingCustomers.size());
        return Optional.of(selected);
    }

    @Override
    public synchronized void markUnassigned(String customerPhone) {
        if (!customerAgentMap.containsKey(customerPhone)) {
            pendingCustomers.add(customerPhone);
            log.info("Mark customer as unassigned. customer={}, pendingCount={}", customerPhone, pendingCustomers.size());
        }
    }


    @Override
    public synchronized void unassignCustomer(String customerPhone) {
        if (customerAgentMap.remove(customerPhone) != null) {
            log.info("Customer unassigned from local map. customer={}, assignedCount={}", customerPhone, customerAgentMap.size());
        }
    }

    @Override
    public synchronized Optional<String> assignOnePendingCustomerToAgent(String agentRowId) {
        if (pendingCustomers.isEmpty()) {
            return Optional.empty();
        }
        String customer = pendingCustomers.iterator().next();
        pendingCustomers.remove(customer);
        customerAgentMap.put(customer, agentRowId);
        log.info("Assign pending customer to newly online agent. customer={}, agent={}, pendingCount={}",
                customer, agentRowId, pendingCustomers.size());
        return Optional.of(customer);
    }

    @Override
    public synchronized Set<String> onlineAgentsSnapshot() {
        return new LinkedHashSet<>(onlineAgents);
    }

    @Override
    public synchronized Map<String, String> assignmentsSnapshot() {
        return new ConcurrentHashMap<>(customerAgentMap);
    }

    @Override
    public synchronized void replaceState(Set<String> onlineAgents, Map<String, String> assignments) {
        this.onlineAgents.clear();
        this.onlineAgents.addAll(onlineAgents);

        this.customerAgentMap.clear();
        this.customerAgentMap.putAll(assignments);

        this.pendingCustomers.clear();
    }
}
