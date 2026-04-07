package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentCustomerView;
import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.ManualMediaReplyRequest;
import com.example.aitmk.model.domain.ManualReplyRequest;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.SendMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    /** CRM 服务（负责落库聊天记录）。 */
    private final CrmOpenApiService crmOpenApiService;
    /** 坐席分配服务（读取客户归属坐席）。 */
    private final AgentDispatchService agentDispatchService;

    /**
     * 拉取客户列表，按最近消息时间倒序返回。
     */
    @GetMapping("/customers")
    public ResponseEntity<List<ChatCustomer>> customers() {
        return ResponseEntity.ok(chatHistoryService.listCustomers());
    }


    /**
     * 返回当前坐席服务过的客户列表（包含服务中、已关闭），并附带服务状态。
     */
    @GetMapping("/customers/serving")
    public ResponseEntity<List<AgentCustomerView>> servingCustomers(@RequestParam("agentRowId") String agentRowId) {
        Map<String, String> statusMap = crmOpenApiService.listAgentCustomerServiceStatus(agentRowId);
        List<AgentCustomerView> customers = chatHistoryService.listCustomers().stream()
                .filter(c -> statusMap.containsKey(c.getCustomerId()))
                .map(c -> {
                    String status = statusMap.getOrDefault(c.getCustomerId(), "已关闭");
                    return AgentCustomerView.builder()
                            .customerId(c.getCustomerId())
                            .customerNickname(c.getCustomerNickname())
                            .lastMessage(c.getLastMessage())
                            .lastMessageAt(c.getLastMessageAt())
                            .serviceStatus(status)
                            .canReply("服务中".equals(status))
                            .build();
                })
                .toList();
        return ResponseEntity.ok(customers);
    }

    /**
     * 根据客户 ID 拉取聊天记录，记录中包含客户消息、AI 自动回复与人工回复。
     */
    @GetMapping("/messages")
    public ResponseEntity<List<ChatMessageRecord>> messages(@RequestParam("customerId") String customerId) {
        return ResponseEntity.ok(chatHistoryService.listMessages(customerId));
    }

    /**
     * 上传媒体文件到 Meta，返回 mediaId。
     */
    @PostMapping("/media/upload")
    public ResponseEntity<?> uploadMedia(@RequestParam("from") String from,
                                         @RequestParam("mediaType") String mediaType,
                                         @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "file 不能为空"
            ));
        }
        try {
            String mediaId = sendMessageService.uploadMedia(from, mediaType, file);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "mediaId", mediaId,
                    "filename", file.getOriginalFilename(),
                    "mediaType", mediaType
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * 发送人工回复，同时将该消息写入聊天历史，便于前端即时展示。
     *
     * 24 小时规则：若客户最后一次发送消息距离当前时间超过 24 小时，则禁止人工直接发送。
     */
    @PostMapping("/reply")
    public ResponseEntity<?> reply(@Valid @RequestBody ManualReplyRequest request) {
        Instant lastCustomerTime = chatHistoryService.lastCustomerMessageTime(request.getCustomerId()).orElse(null);
        if (lastCustomerTime == null || Duration.between(lastCustomerTime, Instant.now()).toHours() > 24) {
            crmOpenApiService.updateServingAssignmentReplyable(request.getCustomerId(), false);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "客户最后一次回复已超过24小时，当前不允许直接人工回复"
            ));
        }

        sendMessageService.sendTextMessage(request.getFrom(), request.getCustomerId(), request.getMessage());
        chatHistoryService.recordManualReply(request.getCustomerId(), request.getMessage());
        agentDispatchService.markAgentReplied(request.getCustomerId());

        String assignedAgent = agentDispatchService.getAssignedAgent(request.getCustomerId()).orElse(null);
        crmOpenApiService.addChatRecord(request.getFrom(), request.getCustomerId(), assignedAgent, "人工", request.getMessage());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * 发送人工媒体消息（图片/视频/音频/文件），并写入本地与 CRM 聊天记录。
     *
     * 支持两种发送方式：
     * 1) 传 mediaId（推荐，来自 /api/chat/media/upload 返回值）
     * 2) 传 mediaUrl（兼容旧链路）
     */
    @PostMapping("/reply/media")
    public ResponseEntity<?> mediaReply(@Valid @RequestBody ManualMediaReplyRequest request) {
        Instant lastCustomerTime = chatHistoryService.lastCustomerMessageTime(request.getCustomerId()).orElse(null);
        if (lastCustomerTime == null || Duration.between(lastCustomerTime, Instant.now()).toHours() > 24) {
            crmOpenApiService.updateServingAssignmentReplyable(request.getCustomerId(), false);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "客户最后一次回复已超过24小时，当前不允许直接人工回复"
            ));
        }

        if (!StringUtils.hasText(request.getMediaId()) && !StringUtils.hasText(request.getMediaUrl())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "mediaId 与 mediaUrl 不能同时为空"
            ));
        }

        try {
            sendMessageService.sendMediaMessage(
                    request.getFrom(),
                    request.getCustomerId(),
                    request.getMediaType(),
                    request.getMediaId(),
                    request.getMediaUrl(),
                    request.getFilename(),
                    request.getCaption()
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        String recordMessage = buildMediaRecordMessage(request);
        chatHistoryService.recordManualReply(request.getCustomerId(), recordMessage);
        agentDispatchService.markAgentReplied(request.getCustomerId());

        String assignedAgent = agentDispatchService.getAssignedAgent(request.getCustomerId()).orElse(null);
        crmOpenApiService.addChatRecord(request.getFrom(), request.getCustomerId(), assignedAgent, "人工", recordMessage);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private String buildMediaRecordMessage(ManualMediaReplyRequest request) {
        String mediaType = request.getMediaType() == null ? "media" : request.getMediaType().trim().toLowerCase();
        StringBuilder sb = new StringBuilder("[").append(mediaType).append("] ");
        if (StringUtils.hasText(request.getMediaId())) {
            sb.append("mediaId=").append(request.getMediaId());
        }
        if (StringUtils.hasText(request.getMediaUrl())) {
            if (sb.charAt(sb.length() - 1) != ' ') {
                sb.append(' ');
            }
            sb.append("url=").append(request.getMediaUrl());
        }
        if (StringUtils.hasText(request.getFilename())) {
            sb.append(" filename=").append(request.getFilename());
        }
        if (StringUtils.hasText(request.getCaption())) {
            sb.append(" caption=").append(request.getCaption());
        }
        return sb.toString();
    }
}
