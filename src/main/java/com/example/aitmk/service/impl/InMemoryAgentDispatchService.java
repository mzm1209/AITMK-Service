package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0); // 默认层级兜底轮询指针

    /** agent -> profile（等级、权重、负载）。 */
    private final Map<String, AgentProfile> agentProfiles = new ConcurrentHashMap<>();
    /** customer -> 最近客户消息时间。 */
    private final Map<String, Long> customerLastMessageAtMillis = new ConcurrentHashMap<>();
    /** customer -> 最近坐席回复时间。 */
    private final Map<String, Long> customerLastAgentReplyAtMillis = new ConcurrentHashMap<>();
    /** customer -> 是否已发出 5 分钟提醒。 */
    private final Set<String> warnedCustomers = ConcurrentHashMap.newKeySet();
    /** 层级内轮询游标。 */
    private final Map<String, AtomicInteger> levelRoundRobin = new ConcurrentHashMap<>();

    @Override
    public synchronized void markOnline(String agentRowId) {
        onlineAgents.add(agentRowId);
        log.info("Agent online. agent={}, onlineCount={}", agentRowId, onlineAgents.size());
    }

    @Override
    public synchronized void markOffline(String agentRowId) {
        onlineAgents.remove(agentRowId);
        // 坐席下线时释放其负责会话，进入待分配队列（AI兜底逻辑由上层编排）
        Set<String> released = new HashSet<>();
        customerAgentMap.forEach((customer, agent) -> {
            if (agentRowId.equals(agent)) {
                released.add(customer);
            }
        });
        released.forEach(customer -> {
            customerAgentMap.remove(customer);
            pendingCustomers.add(customer);
        });
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

        String selected = selectByLayeredScore();
        if (selected == null) {
            ArrayList<String> queue = new ArrayList<>(onlineAgents);
            int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), queue.size());
            selected = queue.get(idx);
        }
        customerAgentMap.put(customerPhone, selected);
        pendingCustomers.remove(customerPhone);
        warnedCustomers.remove(customerPhone);
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
        warnedCustomers.remove(customerPhone);
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
        this.warnedCustomers.clear();
        this.customerLastMessageAtMillis.clear();
        this.customerLastAgentReplyAtMillis.clear();
    }

    @Override
    public void setAgentProfile(String agentRowId, String level, double weight, int maxLoad) {
        if (agentRowId == null || agentRowId.isBlank()) {
            return;
        }
        String normalizedLevel = normalizeLevel(level);
        int normalizedMaxLoad = Math.max(maxLoad, 1);
        double normalizedWeight = weight <= 0 ? 1.0d : weight;
        agentProfiles.put(agentRowId, new AgentProfile(normalizedLevel, normalizedWeight, normalizedMaxLoad));
    }

    @Override
    public void markCustomerMessageAt(String customerPhone) {
        if (customerPhone == null || customerPhone.isBlank()) {
            return;
        }
        customerLastMessageAtMillis.put(customerPhone, System.currentTimeMillis());
        warnedCustomers.remove(customerPhone);
    }

    @Override
    public void markAgentReplied(String customerPhone) {
        if (customerPhone == null || customerPhone.isBlank()) {
            return;
        }
        customerLastAgentReplyAtMillis.put(customerPhone, System.currentTimeMillis());
        warnedCustomers.remove(customerPhone);
    }

    @Override
    public synchronized TimeoutScanResult scanTimeouts(int warnMinutes, int reclaimMinutes) {
        long now = System.currentTimeMillis();
        long warnMillis = Math.max(1, warnMinutes) * 60_000L;
        long reclaimMillis = Math.max(warnMinutes + 1, reclaimMinutes) * 60_000L;

        Set<String> warn = new LinkedHashSet<>();
        Set<String> reclaimed = new LinkedHashSet<>();

        for (Map.Entry<String, String> entry : customerAgentMap.entrySet()) {
            String customer = entry.getKey();
            long customerMsgAt = customerLastMessageAtMillis.getOrDefault(customer, -1L);
            if (customerMsgAt <= 0) {
                continue;
            }
            long agentReplyAt = customerLastAgentReplyAtMillis.getOrDefault(customer, -1L);
            if (agentReplyAt >= customerMsgAt) {
                continue;
            }
            long idle = now - customerMsgAt;
            if (idle >= reclaimMillis) {
                reclaimed.add(customer);
            } else if (idle >= warnMillis && !warnedCustomers.contains(customer)) {
                warn.add(customer);
            }
        }

        warn.forEach(warnedCustomers::add);
        reclaimed.forEach(customer -> {
            customerAgentMap.remove(customer);
            pendingCustomers.add(customer);
            warnedCustomers.remove(customer);
        });
        return new TimeoutScanResult(warn, reclaimed);
    }

    private String selectByLayeredScore() {
        Map<String, Integer> onlineByLevel = new HashMap<>();
        Map<String, Integer> assignedByLevel = new HashMap<>();
        Map<String, Integer> levelCapacity = new HashMap<>();

        for (String agent : onlineAgents) {
            AgentProfile profile = profileOf(agent);
            onlineByLevel.merge(profile.level(), 1, Integer::sum);
            levelCapacity.merge(profile.level(), profile.maxLoad(), Integer::sum);
        }
        customerAgentMap.forEach((customer, agent) -> {
            AgentProfile profile = profileOf(agent);
            assignedByLevel.merge(profile.level(), 1, Integer::sum);
        });

        String bestLevel = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (String level : onlineByLevel.keySet()) {
            int onlineCount = onlineByLevel.getOrDefault(level, 0);
            int assigned = assignedByLevel.getOrDefault(level, 0);
            int capacity = levelCapacity.getOrDefault(level, 0);
            int remain = capacity - assigned;
            if (remain <= 0 || onlineCount <= 0) {
                continue;
            }
            double levelWeight = defaultLevelFactor(level);
            double configuredWeight = averageWeightByLevel(level);
            double score = levelWeight * configuredWeight * remain;
            if (score > bestScore) {
                bestScore = score;
                bestLevel = level;
            }
        }
        if (bestLevel == null) {
            return null;
        }
        return selectAgentInLevel(bestLevel);
    }

    private String selectAgentInLevel(String level) {
        ArrayList<String> candidates = new ArrayList<>();
        for (String agent : onlineAgents) {
            AgentProfile profile = profileOf(agent);
            if (!level.equals(profile.level())) {
                continue;
            }
            long currentLoad = customerAgentMap.values().stream().filter(agent::equals).count();
            if (currentLoad < profile.maxLoad()) {
                candidates.add(agent);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        AtomicInteger idx = levelRoundRobin.computeIfAbsent(level, k -> new AtomicInteger(0));
        return candidates.get(Math.floorMod(idx.getAndIncrement(), candidates.size()));
    }

    private double averageWeightByLevel(String level) {
        double total = 0d;
        int count = 0;
        for (String agent : onlineAgents) {
            AgentProfile p = profileOf(agent);
            if (level.equals(p.level())) {
                total += p.weight();
                count++;
            }
        }
        return count == 0 ? 1.0d : total / count;
    }

    private AgentProfile profileOf(String agent) {
        return agentProfiles.getOrDefault(agent, new AgentProfile("中级", 1.0d, 8));
    }

    private double defaultLevelFactor(String level) {
        return switch (normalizeLevel(level)) {
            case "高级" -> 1.1d;
            case "初级" -> 0.9d;
            default -> 1.0d;
        };
    }

    private String normalizeLevel(String level) {
        if (level == null) {
            return "中级";
        }
        String v = level.trim();
        if ("高级".equals(v) || "中级".equals(v) || "初级".equals(v)) {
            return v;
        }
        return "中级";
    }

    private record AgentProfile(String level, double weight, int maxLoad) {}
}
