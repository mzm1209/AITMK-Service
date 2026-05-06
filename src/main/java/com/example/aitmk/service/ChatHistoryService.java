package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.PageResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * 聊天历史服务抽象：
 * 负责统一记录客户侧、AI 侧、人工侧消息，并提供查询接口。
 */
public interface ChatHistoryService {

    /** 记录客户发来的消息。 */
    void recordCustomerMessage(String customerId, String message);

    /** 更新客户昵称。 */
    void setCustomerNickname(String customerId, String nickname);

    /** 记录 AI 自动回复消息。 */
    void recordAiReply(String customerId, String message);

    /** 记录人工客服回复消息。 */
    void recordManualReply(String customerId, String message);

    /** 查询客户列表（通常按最后活跃时间排序）。 */
    List<ChatCustomer> listCustomers();

    /** 查询指定客户的消息明细。 */
    List<ChatMessageRecord> listMessages(String customerId);

    /** 分页查询指定客户消息。 */
    PageResult<ChatMessageRecord> listMessagesPaged(String customerId, int page, int size, boolean desc);

    /** 获取客户最后一条“客户发送”的消息时间。 */
    Optional<Instant> lastCustomerMessageTime(String customerId);

    /** 获取全量历史快照（用于同步/恢复）。 */
    Map<String, List<ChatMessageRecord>> snapshot();

    /** 用快照覆盖当前缓存。 */
    void replaceAll(Map<String, List<ChatMessageRecord>> records);
}
