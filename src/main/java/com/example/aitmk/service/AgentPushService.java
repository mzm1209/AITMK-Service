package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatMessageRecord;

import java.util.List;

public interface AgentPushService {

    void pushHistory(String agentRowId, String customerPhone, List<ChatMessageRecord> messages);

    void pushNewMessage(String agentRowId, String customerPhone, ChatMessageRecord message);
}
