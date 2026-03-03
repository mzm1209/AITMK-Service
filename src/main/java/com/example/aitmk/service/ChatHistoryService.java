package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;

import java.util.List;

/**
 * 聊天历史服务抽象：
 * 负责统一记录客户侧、AI 侧、人工侧消息，并提供查询接口。
 */
public interface ChatHistoryService {

    /** 记录客户发来的消息。 */
    void recordCustomerMessage(String customerId, String message);

    /** 记录 AI 自动回复消息。 */
    void recordAiReply(String customerId, String message);

    /** 记录人工客服回复消息。 */
    void recordManualReply(String customerId, String message);

    /** 查询客户列表（通常按最后活跃时间排序）。 */
    List<ChatCustomer> listCustomers();

    /** 查询指定客户的消息明细。 */
    List<ChatMessageRecord> listMessages(String customerId);
}
