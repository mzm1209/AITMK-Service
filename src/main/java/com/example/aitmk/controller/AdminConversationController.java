package com.example.aitmk.controller;

import com.example.aitmk.model.api.ApiErrorResponse;
import com.example.aitmk.model.domain.AdminConversationJoinRequest;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.security.permission.ChatPermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/conversations")
@RequiredArgsConstructor
public class AdminConversationController {

    private static final Set<String> SUPPORTED_MODES = Set.of("monitor_only", "take_over", "assist");

    private final ChatPermissionService chatPermissionService;

    @PostMapping("/{customerId}/join")
    public ResponseEntity<?> joinConversation(@PathVariable String customerId,
                                              @Valid @RequestBody AdminConversationJoinRequest request) {
        var user = CurrentUser.get();
        if (!chatPermissionService.canJoinConversation(user, customerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiErrorResponse.of("FORBIDDEN", "无权插入该客户会话"));
        }
        String mode = request.getMode() == null ? "" : request.getMode().trim();
        if (!SUPPORTED_MODES.contains(mode)) {
            return ResponseEntity.badRequest()
                    .body(ApiErrorResponse.of("BAD_REQUEST", "mode 仅支持 monitor_only/take_over/assist"));
        }

        // TODO: CRM 聊天记录表增加 operatorId/operatorRole 后，在这里写入插入会话审计记录。
        return ResponseEntity.ok(Map.of(
                "success", true,
                "customerId", customerId,
                "mode", mode,
                "operatorId", user.getAccountRowId(),
                "operatorRole", user.getRole().name(),
                "remark", StringUtils.hasText(request.getRemark()) ? request.getRemark() : ""
        ));
    }
}
