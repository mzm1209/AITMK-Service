package com.example.aitmk.controller;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.domain.LoginRequest;
import com.example.aitmk.model.domain.LoginResponse;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

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

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        Optional<CrmAgentAccount> account = crmOpenApiService.verifyLogin(request.getUsername(), request.getPassword());
        if (account.isEmpty()) {
            return ResponseEntity.ok(LoginResponse.builder()
                    .success(false)
                    .message("登录失败")
                    .build());
        }

        CrmAgentAccount agent = account.get();
        crmOpenApiService.addAgentLoginRecord(agent.getRowId(), "在线");
        agentDispatchService.markOnline(agent.getRowId());

        return ResponseEntity.ok(LoginResponse.builder()
                .success(true)
                .message("登录成功")
                .accountRowId(agent.getRowId())
                .relatedUserIds(agent.getRelatedUserIds())
                .build());
    }
}
