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

    Optional<String> assignOnePendingCustomerToAgent(String agentRowId);

    Set<String> onlineAgentsSnapshot();

    Map<String, String> assignmentsSnapshot();

    void replaceState(Set<String> onlineAgents, Map<String, String> assignments);
}
