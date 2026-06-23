package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentCustomerView;
import com.example.aitmk.model.api.ApiErrorResponse;
import com.example.aitmk.model.domain.ChatCustomer;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.ManualMediaReplyRequest;
import com.example.aitmk.model.domain.PageResult;
import com.example.aitmk.model.domain.ManualReplyRequest;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.security.permission.ChatPermissionService;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.SendMessageService;
import com.example.aitmk.service.MessagePersistenceService;
import com.example.aitmk.service.AssignmentPersistenceService;
import com.example.aitmk.model.entity.PersistenceEnums.MessageType;
import com.example.aitmk.model.entity.PersistenceEnums.SenderType;
import com.example.aitmk.service.impl.AgentSessionActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    private final AgentSessionActivityService sessionActivityService;
    private final ChatPermissionService chatPermissionService;
    private final MessagePersistenceService messagePersistenceService;
    private final AssignmentPersistenceService assignmentPersistenceService;

    /**
     * 拉取客户列表，按最近消息时间倒序返回。
     */
    @GetMapping("/customers")
    public ResponseEntity<List<ChatCustomer>> customers() {
        AuthenticatedUser user = CurrentUser.get();
        sessionActivityService.touch(user.getAccountRowId());
        return ResponseEntity.ok(chatHistoryService.listCustomers().stream()
                .filter(customer -> chatPermissionService.canViewCustomer(user, customer.getCustomerId()))
                .toList());
    }


    /**
     * 返回当前坐席服务过的客户列表（包含服务中、已关闭），并附带服务状态。
     */
    @GetMapping("/customers/serving")
    public ResponseEntity<?> servingCustomers(@RequestParam("agentRowId") String agentRowId) {
        AuthenticatedUser user = CurrentUser.get();
        if (!chatPermissionService.canViewAgent(user, agentRowId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权查看该坐席会话"));
        }
        sessionActivityService.touch(user.getAccountRowId());
        return ResponseEntity.ok(listAgentCustomers(agentRowId, user));
    }


    /**
     * 返回当前坐席会话列表（最近消息倒序），分页查询。
     */
    @GetMapping("/conversations")
    public ResponseEntity<PageResult<AgentCustomerView>> conversations(@RequestParam("agentRowId") String agentRowId,
                                                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                                                       @RequestParam(value = "size", defaultValue = "20") int size,
                                                                       @RequestParam(value = "status", defaultValue = "all") String status,
                                                                       @RequestParam(value = "keyword", required = false) String keyword) {
        AuthenticatedUser user = CurrentUser.get();
        if (!chatPermissionService.canViewAgent(user, agentRowId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(PageResult.<AgentCustomerView>builder()
                    .items(List.of())
                    .page(Math.max(page, 1))
                    .size(Math.min(Math.max(size, 1), 50))
                    .total(0)
                    .hasNext(false)
                    .build());
        }
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        sessionActivityService.touch(user.getAccountRowId());

        Map<String, String> activeAssignments = assignmentPersistenceService.currentAssignments();
        List<AgentCustomerView> filtered = chatHistoryService.listCustomers().stream()
                .filter(c -> assignmentPersistenceService.hasServed(c.getCustomerId(), agentRowId))
                .filter(c -> chatPermissionService.canViewCustomer(user, c.getCustomerId()))
                .map(c -> {
                    String serviceStatus = agentRowId.equals(activeAssignments.get(c.getCustomerId())) ? "服务中" : "已关闭";
                    return AgentCustomerView.builder()
                            .customerId(c.getCustomerId())
                            .customerNickname(c.getCustomerNickname())
                            .lastMessage(c.getLastMessage())
                            .lastMessageAt(c.getLastMessageAt())
                            .serviceStatus(serviceStatus)
                            .canReply("服务中".equals(serviceStatus))
                            .build();
                })
                .filter(v -> {
                    if (!StringUtils.hasText(status) || "all".equalsIgnoreCase(status)) {
                        return true;
                    }
                    if ("serving".equalsIgnoreCase(status)) {
                        return "服务中".equals(v.getServiceStatus());
                    }
                    if ("closed".equalsIgnoreCase(status)) {
                        return "已关闭".equals(v.getServiceStatus());
                    }
                    return true;
                })
                .filter(v -> {
                    if (!StringUtils.hasText(keyword)) {
                        return true;
                    }
                    String k = keyword.trim();
                    return v.getCustomerId().contains(k) || (v.getCustomerNickname() != null && v.getCustomerNickname().contains(k));
                })
                .toList();

        int total = filtered.size();
        int from = (safePage - 1) * safeSize;
        List<AgentCustomerView> items = from >= total ? List.of() : filtered.subList(from, Math.min(from + safeSize, total));
        return ResponseEntity.ok(PageResult.<AgentCustomerView>builder()
                .items(items)
                .page(safePage)
                .size(safeSize)
                .total(total)
                .hasNext(from + safeSize < total)
                .build());
    }

    /**
     * 查询指定客户聊天记录（倒序分页），每页最多 50。
     */
    @GetMapping("/conversations/{customerId}/messages")
    public ResponseEntity<?> conversationMessages(@org.springframework.web.bind.annotation.PathVariable("customerId") String customerId,
                                                  @RequestParam(value = "agentRowId", required = false) String agentRowId,
                                                  @RequestParam(value = "page", defaultValue = "1") int page,
                                                  @RequestParam(value = "size", defaultValue = "20") int size) {
        AuthenticatedUser user = CurrentUser.get();
        sessionActivityService.touch(user.getAccountRowId());
        if (!chatPermissionService.canViewCustomer(user, customerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权查看该客户会话"));
        }
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        return ResponseEntity.ok(chatHistoryService.listMessagesPaged(customerId, safePage, safeSize, true));
    }
    /**
     * 根据客户 ID 拉取聊天记录，记录中包含客户消息、AI 自动回复与人工回复。
     */
    @GetMapping("/messages")
    public ResponseEntity<?> messages(@RequestParam("customerId") String customerId) {
        AuthenticatedUser user = CurrentUser.get();
        sessionActivityService.touch(user.getAccountRowId());
        if (!chatPermissionService.canViewCustomer(user, customerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权查看该客户会话"));
        }
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
        sessionActivityService.touch(CurrentUser.get().getAccountRowId());
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
        AuthenticatedUser user = CurrentUser.get();
        sessionActivityService.touch(user.getAccountRowId());
        if (!chatPermissionService.canReplyCustomer(user, request.getCustomerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权回复该客户会话"));
        }
        Instant lastCustomerTime = chatHistoryService.lastCustomerMessageTime(request.getCustomerId()).orElse(null);
        if (lastCustomerTime == null || Duration.between(lastCustomerTime, Instant.now()).toHours() > 24) {
            crmOpenApiService.updateServingAssignmentReplyable(request.getCustomerId(), false);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "客户最后一次回复已超过24小时，当前不允许直接人工回复"
            ));
        }

        long localMessageId = messagePersistenceService.createOutgoing(request.getCustomerId(), request.getFrom(),
                user.getRole() == AgentRole.TMK ? SenderType.AGENT : SenderType.MANAGER,
                user.getAccountRowId(), user.getRole().name(), MessageType.TEXT, request.getMessage(), null, null, null);
        sendMessageService.sendTextMessage(request.getFrom(), request.getCustomerId(), request.getMessage(), localMessageId);
        agentDispatchService.markAgentReplied(request.getCustomerId());

        String assignedAgent = agentDispatchService.getAssignedAgent(request.getCustomerId())
                .or(() -> crmOpenApiService.findServingAgentRowId(request.getCustomerId()))
                .orElse(user.getAccountRowId());
        sessionActivityService.touch(assignedAgent);
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
        AuthenticatedUser user = CurrentUser.get();
        sessionActivityService.touch(user.getAccountRowId());
        if (!chatPermissionService.canReplyCustomer(user, request.getCustomerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权回复该客户会话"));
        }
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

        String recordMessage = buildMediaRecordMessage(request);
        long localMessageId = messagePersistenceService.createOutgoing(request.getCustomerId(), request.getFrom(),
                user.getRole() == AgentRole.TMK ? SenderType.AGENT : SenderType.MANAGER,
                user.getAccountRowId(), user.getRole().name(), parseMessageType(request.getMediaType()), recordMessage,
                request.getMediaId(), request.getMediaUrl(), null);
        try {
            sendMessageService.sendMediaMessage(
                    request.getFrom(),
                    request.getCustomerId(),
                    request.getMediaType(),
                    request.getMediaId(),
                    request.getMediaUrl(),
                    request.getFilename(),
                    request.getCaption(),
                    localMessageId
            );
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", e.getMessage()
            ));
        }

        agentDispatchService.markAgentReplied(request.getCustomerId());

        String assignedAgent = agentDispatchService.getAssignedAgent(request.getCustomerId())
                .or(() -> crmOpenApiService.findServingAgentRowId(request.getCustomerId()))
                .orElse(user.getAccountRowId());
        sessionActivityService.touch(assignedAgent);
        crmOpenApiService.addChatRecord(request.getFrom(), request.getCustomerId(), assignedAgent, "人工", recordMessage);
        return ResponseEntity.ok(Map.of("success", true));
    }

    private MessageType parseMessageType(String type) {
        if (!StringUtils.hasText(type)) return MessageType.SYSTEM;
        try { return MessageType.valueOf(type.trim().toUpperCase()); }
        catch (IllegalArgumentException ignored) { return MessageType.SYSTEM; }
    }

    private List<AgentCustomerView> listAgentCustomers(String agentRowId, AuthenticatedUser user) {
        Map<String, String> activeAssignments = assignmentPersistenceService.currentAssignments();
        if (user.getRole() == AgentRole.OWNER || user.getRole() == AgentRole.MANAGER) {
            return chatHistoryService.listCustomers().stream()
                    .filter(c -> chatPermissionService.canViewCustomer(user, c.getCustomerId()))
                    .map(c -> {
                        String status = agentRowId.equals(activeAssignments.get(c.getCustomerId())) ? "服务中" : "已关闭";
                        return toAgentCustomerView(c, status);
                    })
                    .toList();
        }
        return chatHistoryService.listCustomers().stream()
                .filter(c -> assignmentPersistenceService.hasServed(c.getCustomerId(), agentRowId))
                .map(c -> toAgentCustomerView(c, agentRowId.equals(activeAssignments.get(c.getCustomerId())) ? "服务中" : "已关闭"))
                .toList();
    }

    private AgentCustomerView toAgentCustomerView(ChatCustomer customer, String status) {
        return AgentCustomerView.builder()
                .customerId(customer.getCustomerId())
                .customerNickname(customer.getCustomerNickname())
                .lastMessage(customer.getLastMessage())
                .lastMessageAt(customer.getLastMessageAt())
                .serviceStatus(status)
                .canReply("服务中".equals(status))
                .build();
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
