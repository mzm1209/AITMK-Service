package com.example.aitmk.controller;

import com.example.aitmk.model.api.ApiErrorResponse;
import com.example.aitmk.model.domain.WsReconnectRequest;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.security.permission.ChatPermissionService;
import com.example.aitmk.service.AgentPushService;
import com.example.aitmk.service.impl.AgentSessionActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 坐席辅助接口。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentPushService agentPushService;
    private final AgentSessionActivityService sessionActivityService;
    private final ChatPermissionService chatPermissionService;

    /**
     * 客户端 WebSocket 重连成功后主动通知服务端，触发失败消息重推。
     */
    @PostMapping("/ws/reconnected")
    public ResponseEntity<?> wsReconnected(@Valid @RequestBody WsReconnectRequest request) {
        var user = CurrentUser.get();
        if (!chatPermissionService.canViewAgent(user, request.getAgentRowId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "只能重连当前账号的 WebSocket"));
        }
        sessionActivityService.touch(request.getAgentRowId());
        agentPushService.resendFailed(request.getAgentRowId());
        return ResponseEntity.ok().build();
    }

    /**
     * Web 端活跃心跳：建议每 20~30 秒上报一次，避免误判自动离线。
     */
    @PostMapping("/activity/ping")
    public ResponseEntity<?> activityPing(@RequestParam(value = "agentRowId", required = false) String agentRowId) {
        var user = CurrentUser.get();
        if (agentRowId != null && !agentRowId.isBlank() && !user.getAccountRowId().equals(agentRowId.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "只能 ping 当前账号"));
        }
        sessionActivityService.touch(user.getAccountRowId());
        return ResponseEntity.ok().build();
    }
}
