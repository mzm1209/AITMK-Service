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

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatHistoryService chatHistoryService;
    private final SendMessageService sendMessageService;

    @GetMapping("/customers")
    public ResponseEntity<List<ChatCustomer>> customers() {
        return ResponseEntity.ok(chatHistoryService.listCustomers());
    }

    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessageRecord>> messages(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(chatHistoryService.listMessages(customerId));
    }

    @PostMapping("/reply")
    public ResponseEntity<Void> reply(@Valid @RequestBody ManualReplyRequest request) {
        sendMessageService.sendTextMessage(request.getFrom(), request.getCustomerId(), request.getMessage());
        chatHistoryService.recordManualReply(request.getCustomerId(), request.getMessage());
        return ResponseEntity.ok().build();
    }
}
