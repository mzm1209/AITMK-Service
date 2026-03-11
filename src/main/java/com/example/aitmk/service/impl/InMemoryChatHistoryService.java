package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.service.ChatHistoryService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的聊天历史实现：
 * 适用于开发联调与轻量部署场景，服务重启后数据会丢失。
 */
@Service
public class InMemoryChatHistoryService implements ChatHistoryService {

    /**
     * 以客户 ID 作为 Key 存放消息列表。
     * 使用 ConcurrentHashMap 保证并发读写安全。
     */
    private final Map<String, List<ChatMessageRecord>> recordsByCustomer = new ConcurrentHashMap<>();

    @Override
    public void recordCustomerMessage(String customerId, String message) {
        append(customerId, "customer", message);
    }

    @Override
    public void recordAiReply(String customerId, String message) {
        append(customerId, "ai", message);
    }

    @Override
    public void recordManualReply(String customerId, String message) {
        append(customerId, "agent", message);
    }

    /**
     * 返回客户摘要列表：提取每个客户最后一条消息并按时间倒序排序。
     */
    @Override
    public List<ChatCustomer> listCustomers() {
        return recordsByCustomer.entrySet().stream()
                .map(entry -> {
                    List<ChatMessageRecord> history = entry.getValue();
                    if (history == null || history.isEmpty()) {
                        return null;
                    }
                    ChatMessageRecord latest = history.get(history.size() - 1);
                    return ChatCustomer.builder()
                            .customerId(entry.getKey())
                            .lastMessage(latest.getMessage())
                            .lastMessageAt(latest.getTimestamp())
                            .build();
                })
                .filter(customer -> customer != null)
                .sorted(Comparator.comparing(ChatCustomer::getLastMessageAt).reversed())
                .toList();
    }

    /**
     * 返回某个客户的全部消息，拷贝新列表避免调用方误改内部状态。
     */
    @Override
    public List<ChatMessageRecord> listMessages(String customerId) {
        List<ChatMessageRecord> messages = recordsByCustomer.getOrDefault(customerId, List.of());
        return new ArrayList<>(messages);
    }

    @Override
    public Optional<Instant> lastCustomerMessageTime(String customerId) {
        List<ChatMessageRecord> messages = recordsByCustomer.getOrDefault(customerId, List.of());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageRecord rec = messages.get(i);
            if ("customer".equals(rec.getSender())) {
                return Optional.ofNullable(rec.getTimestamp());
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<String, List<ChatMessageRecord>> snapshot() {
        Map<String, List<ChatMessageRecord>> snapshot = new ConcurrentHashMap<>();
        recordsByCustomer.forEach((k, v) -> snapshot.put(k, new ArrayList<>(v)));
        return snapshot;
    }

    @Override
    public void replaceAll(Map<String, List<ChatMessageRecord>> records) {
        recordsByCustomer.clear();
        records.forEach((k, v) -> recordsByCustomer.put(k, new ArrayList<>(v)));
    }

    /**
     * 统一追加消息记录。
     */
    private void append(String customerId, String sender, String message) {
        recordsByCustomer.compute(customerId, (key, records) -> {
            List<ChatMessageRecord> updated = records == null ? new ArrayList<>() : new ArrayList<>(records);
            updated.add(ChatMessageRecord.builder()
                    .customerId(customerId)
                    .sender(sender)
                    .message(message)
                    .timestamp(Instant.now())
                    .build());
            return updated;
        });
    }
}
