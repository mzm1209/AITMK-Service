package com.example.aitmk.controller;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.security.auth.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 群发管理骨架。
 * 当前仅提供空实现，后续实现完整的创建/提交/查询流程。
 */
@Slf4j
@RestController
@RequestMapping("/api/broadcast")
@RequiredArgsConstructor
public class BroadcastController {

    @PostMapping("/tasks")
    public Response<?> createTask() {
        // TODO: 创建群发任务，权限 BROADCAST_MANAGE
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }

    @PostMapping("/tasks/{id}/submit")
    public Response<?> submitTask(@PathVariable Long id) {
        // TODO: 提交发送，异步遍历收件人
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }

    @GetMapping("/tasks")
    public Response<?> listTasks() {
        // TODO: 任务列表
        return Response.ok(java.util.Map.of("items", java.util.List.of(), "status", "not_implemented"));
    }

    @GetMapping("/tasks/{id}")
    public Response<?> getTask(@PathVariable Long id) {
        // TODO: 任务详情 + 汇总
        return Response.ok(java.util.Map.of("status", "not_implemented"));
    }

    @GetMapping("/tasks/{id}/recipients")
    public Response<?> listRecipients(@PathVariable Long id) {
        // TODO: 收件人列表（游标分页）
        return Response.ok(java.util.Map.of("items", java.util.List.of(), "status", "not_implemented"));
    }
}