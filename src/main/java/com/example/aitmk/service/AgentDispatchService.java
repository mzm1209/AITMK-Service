package com.example.aitmk.service;

import java.util.Optional;

public interface AgentDispatchService {

    void markOnline(String agentRowId);

    boolean hasOnlineAgent();

    Optional<String> getAssignedAgent(String customerPhone);

    Optional<String> assignIfAbsent(String customerPhone);
}
