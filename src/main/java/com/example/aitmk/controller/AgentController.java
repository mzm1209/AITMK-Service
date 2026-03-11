package com.example.aitmk.controller;

import com.example.aitmk.model.domain.WsReconnectRequest;
import com.example.aitmk.service.AgentPushService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 坐席辅助接口。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentPushService agentPushService;

    /**
     * 客户端 WebSocket 重连成功后主动通知服务端，触发失败消息重推。
     */
    @PostMapping("/ws/reconnected")
    public ResponseEntity<Void> wsReconnected(@Valid @RequestBody WsReconnectRequest request) {
        agentPushService.resendFailed(request.getAgentRowId());
        return ResponseEntity.ok().build();
    }
}
