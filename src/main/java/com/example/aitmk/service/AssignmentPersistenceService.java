package com.example.aitmk.service;

import java.util.Map;
import java.util.Optional;

public interface AssignmentPersistenceService {
    Optional<String> currentAgent(String customerPhone);
    Map<String, String> currentAssignments();
    boolean hasServed(String customerPhone, String agentId);
}
