package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 资源状态变更控制器（带审计日志）。
 * 提供资源状态/类型/标签的变更接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/resources")
@RequiredArgsConstructor
public class ResourceStatusController {

    @PutMapping("/{id}/status")
    public Response<?> updateStatus(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        // TODO: 变更 resourceStatus，写 audit_log
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }

    @PutMapping("/{id}/type")
    public Response<?> updateType(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        // TODO: 变更 resourceType，写 audit_log
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }

    @PostMapping("/{id}/tags")
    public Response<?> updateTags(@PathVariable Long id, @RequestBody java.util.Map<String, Object> body) {
        // TODO: 标签操作
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }
}