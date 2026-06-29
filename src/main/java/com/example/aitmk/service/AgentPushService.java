package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatMessageRecord;

import java.util.List;

public interface AgentPushService {

    void pushHistory(String agentRowId, String customerPhone, List<ChatMessageRecord> messages);

    void pushNewMessage(String agentRowId, String customerPhone, ChatMessageRecord message);

    /** 坐席客户端重连后，重发之前推送失败的消息。 */
    void resendFailed(String agentRowId);

    /** Push lead info from CRM leads_bank to the assigned agent. */
    void pushLeadInfo(String agentRowId, String customerPhone, com.example.aitmk.model.domain.LeadRecord lead);
}
