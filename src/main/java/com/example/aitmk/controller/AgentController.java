package com.example.aitmk.controller;

import com.example.aitmk.model.domain.WsReconnectRequest;
import com.example.aitmk.service.AgentPushService;
import com.example.aitmk.service.impl.AgentSessionActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    /**
     * 客户端 WebSocket 重连成功后主动通知服务端，触发失败消息重推。
     */
    @PostMapping("/ws/reconnected")
    public ResponseEntity<Void> wsReconnected(@Valid @RequestBody WsReconnectRequest request) {
        sessionActivityService.touch(request.getAgentRowId());
        agentPushService.resendFailed(request.getAgentRowId());
        return ResponseEntity.ok().build();
    }

    /**
     * Web 端活跃心跳：建议每 20~30 秒上报一次，避免误判自动离线。
     */
    @PostMapping("/activity/ping")
    public ResponseEntity<Void> activityPing(@RequestParam("agentRowId") String agentRowId) {
        sessionActivityService.touch(agentRowId);
        return ResponseEntity.ok().build();
    }
}
