package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatMessageRecord;

import java.util.List;

public interface AgentPushService {

    void pushHistory(String agentRowId, String customerPhone, List<ChatMessageRecord> messages);

    void pushNewMessage(String agentRowId, String customerPhone, ChatMessageRecord message);

    /** 坐席客户端重连后，重发之前推送失败的消息。 */
    void resendFailed(String agentRowId);
}
