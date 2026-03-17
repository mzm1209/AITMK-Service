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

    Optional<String> assignOnePendingCustomerToAgent(String agentRowId);

    Set<String> onlineAgentsSnapshot();

    Map<String, String> assignmentsSnapshot();

    void replaceState(Set<String> onlineAgents, Map<String, String> assignments);
}
