package com.example.aitmk.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface AgentDispatchService {

    void markOnline(String agentRowId);

    void markOffline(String agentRowId);

    boolean hasOnlineAgent();

    Optional<String> getAssignedAgent(String customerPhone);

    Optional<String> assignIfAbsent(String customerPhone);

    void markUnassigned(String customerPhone);

    /**
     * 释放指定客户当前本地分配关系（如会话超时关闭后）。
     */
    void unassignCustomer(String customerPhone);

    /** Atomically transfer the active assignment to another agent. */
    default Optional<String> transferCustomer(String customerPhone, String targetAgentRowId, String assignedBy) {
        return Optional.empty();
    }

    Optional<String> assignOnePendingCustomerToAgent(String agentRowId);

    Set<String> onlineAgentsSnapshot();

    Map<String, String> assignmentsSnapshot();

    void replaceState(Set<String> onlineAgents, Map<String, String> assignments);

    /**
     * 为坐席设置分层分配画像（等级/权重/负载）。
     */
    void setAgentProfile(String agentRowId, String level, double weight, int maxLoad);

    /**
     * 记录客户消息时间（用于超时提醒/回收）。
     */
    void markCustomerMessageAt(String customerPhone);

    /**
     * 记录坐席回复时间（用于清理超时状态）。
     */
    void markAgentReplied(String customerPhone);



}
