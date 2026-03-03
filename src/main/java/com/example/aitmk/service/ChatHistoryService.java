package com.example.aitmk.service;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;

import java.util.List;

public interface ChatHistoryService {

    void recordCustomerMessage(String customerId, String message);

    void recordAiReply(String customerId, String message);

    void recordManualReply(String customerId, String message);

    List<ChatCustomer> listCustomers();

    List<ChatMessageRecord> listMessages(String customerId);
}
