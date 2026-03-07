package com.example.aitmk.service;

import com.example.aitmk.model.domain.CrmAgentAccount;

import java.util.Optional;

public interface CrmOpenApiService {

    Optional<CrmAgentAccount> verifyLogin(String username, String password);

    boolean addAgentLoginRecord(String agentAccountRowId, String status);

    boolean addAssignmentRecord(String customerPhone, String agentAccountRowId, String serviceStatus);

    boolean addChatRecord(String businessAccountId,
                          String customerPhone,
                          String agentAccountRowId,
                          String sender,
                          String message);
}
