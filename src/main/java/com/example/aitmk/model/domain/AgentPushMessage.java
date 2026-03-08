package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 推送给坐席客户端的消息体。
 */
@Data
@Builder
public class AgentPushMessage {

    /** 推送类型：history / new_message */
    private String type;
    private String agentRowId;
    private String customerPhone;
    private List<ChatMessageRecord> messages;
}
