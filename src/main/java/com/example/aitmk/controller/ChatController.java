package com.example.aitmk.controller;

import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.ManualReplyRequest;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.SendMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 聊天管理接口：提供客户列表、聊天记录查询与人工回复能力。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /** 聊天历史服务（负责读取/写入会话消息）。 */
    private final ChatHistoryService chatHistoryService;
    /** 消息发送服务（负责调用 WhatsApp 发送人工消息）。 */
    private final SendMessageService sendMessageService;

    /**
     * 拉取客户列表，按最近消息时间倒序返回。
     */
    @GetMapping("/customers")
    public ResponseEntity<List<ChatCustomer>> customers() {
        return ResponseEntity.ok(chatHistoryService.listCustomers());
    }

    /**
     * 根据客户 ID 拉取聊天记录，记录中包含客户消息、AI 自动回复与人工回复。
     */
    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessageRecord>> messages(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(chatHistoryService.listMessages(customerId));
    }

    /**
     * 发送人工回复，同时将该消息写入聊天历史，便于前端即时展示。
     */
    @PostMapping("/reply")
    public ResponseEntity<Void> reply(@Valid @RequestBody ManualReplyRequest request) {
        sendMessageService.sendTextMessage(request.getFrom(), request.getCustomerId(), request.getMessage());
        chatHistoryService.recordManualReply(request.getCustomerId(), request.getMessage());
        return ResponseEntity.ok().build();
    }
}
