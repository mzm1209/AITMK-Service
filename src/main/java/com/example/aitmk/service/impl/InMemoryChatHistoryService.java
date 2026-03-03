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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InMemoryChatHistoryService implements ChatHistoryService {

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

    @Override
    public List<ChatMessageRecord> listMessages(String customerId) {
        List<ChatMessageRecord> messages = recordsByCustomer.getOrDefault(customerId, List.of());
        return new ArrayList<>(messages);
    }

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
