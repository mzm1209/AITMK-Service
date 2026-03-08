package com.example.aitmk.controller;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.domain.LoginRequest;
import com.example.aitmk.model.domain.LoginResponse;
import com.example.aitmk.model.domain.LogoutRequest;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.AgentPushService;
import com.example.aitmk.service.CacheSyncService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录服务：
 * 调用 CRM 账号表校验账号密码，成功后写入坐席登录记录并加入坐席队列。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CrmOpenApiService crmOpenApiService;
    private final AgentDispatchService agentDispatchService;
    private final AgentPushService agentPushService;
    private final ChatHistoryService chatHistoryService;
    private final CacheSyncService cacheSyncService;

    /** 登录记录ID缓存（agentRowId -> loginRecordRowId）。 */
    private final Map<String, String> loginRecordMap = new ConcurrentHashMap<>();

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 登录时进行一次 CRM 同步，减少重启导致的本地缓存空洞
        cacheSyncService.syncFromCrm();

        Optional<CrmAgentAccount> account = crmOpenApiService.verifyLogin(request.getUsername(), request.getPassword());
        if (account.isEmpty()) {
            return ResponseEntity.ok(LoginResponse.builder()
                    .success(false)
                    .message("登录失败")
                    .build());
        }

        CrmAgentAccount agent = account.get();
        Optional<String> loginRecord = crmOpenApiService.addAgentLoginRecord(agent.getRowId(), "在线");
        loginRecord.ifPresent(id -> loginRecordMap.put(agent.getRowId(), id));

        agentDispatchService.markOnline(agent.getRowId());

        // 若存在“未分配客户”，在坐席登录后立即补分配，并推送完整历史
        while (true) {
            Optional<String> pending = agentDispatchService.assignOnePendingCustomerToAgent(agent.getRowId());
            if (pending.isEmpty()) {
                break;
            }
            String customerPhone = pending.get();
            crmOpenApiService.addAssignmentRecord(customerPhone, agent.getRowId(), "服务中");
            agentPushService.pushHistory(agent.getRowId(), customerPhone, chatHistoryService.listMessages(customerPhone));
        }

        return ResponseEntity.ok(LoginResponse.builder()
                .success(true)
                .message("登录成功")
                .accountRowId(agent.getRowId())
                .relatedUserIds(agent.getRelatedUserIds())
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<LoginResponse> logout(@Valid @RequestBody LogoutRequest request) {
        agentDispatchService.markOffline(request.getAgentRowId());

        String loginRecordRowId = loginRecordMap.get(request.getAgentRowId());
        if (loginRecordRowId != null) {
            crmOpenApiService.updateAgentLoginStatus(loginRecordRowId, "离线");
        }

        return ResponseEntity.ok(LoginResponse.builder()
                .success(true)
                .message("登出成功")
                .accountRowId(request.getAgentRowId())
                .build());
    }
}
