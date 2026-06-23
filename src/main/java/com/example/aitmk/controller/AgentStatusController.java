package com.example.aitmk.controller;

import com.example.aitmk.model.api.ApiErrorResponse;
import com.example.aitmk.model.domain.AgentStatusUpdateRequest;
import com.example.aitmk.service.AgentPresence;
import com.example.aitmk.service.AgentPresenceService;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.permission.ChatPermissionService;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.AgentPushService;
import com.example.aitmk.support.CrmRelationIds;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IM Web 坐席状态管理查询接口。
 */
@RestController
@RequestMapping("/api/agent/status")
@RequiredArgsConstructor
public class AgentStatusController {

    private static final String WORKSHEET_ID = "zxzt";
    private static final String AGENT_ROW_ID_CONTROL_ID = "69aea988433ec9f4b5e70086";
    private static final String AGENT_STATUS_CONTROL_ID = "69abbb3e433ec9f4b5e6d085";

    private final CrmOpenApiService crmOpenApiService;
    private final AgentDispatchService agentDispatchService;
    private final AgentPushService agentPushService;
    private final ChatHistoryService chatHistoryService;
    private final ChatPermissionService chatPermissionService;
    private final AgentPresenceService presenceService;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentStatusController.class);


    @PostMapping("/update")
    public ResponseEntity<?> updateStatus(@Valid @RequestBody AgentStatusUpdateRequest request) {
        var user = CurrentUser.get();
        if (!user.getAccountRowId().equals(request.getAgentRowId().trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "只能更新当前账号状态"));
        }
        String status = request.getStatus().trim();
        AgentPresence target = AgentPresence.fromString(status);
        // 无效状态文本检查：fromString 会回退到 OFFLINE，但我们要区分显式传入的无效值
        if (target == AgentPresence.OFFLINE && !"离线".equals(status) && !"offline".equalsIgnoreCase(status)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "仅支持 在线/挂机/离线"));
        }

        // 1. 先更新本地状态（立即生效）
        presenceService.changeStatus(request.getAgentRowId().trim(), target);

        // 2. CRM 异步更新（捕获异常，不阻塞主流程）
        crmOpenApiService.findActiveLoginRecordRowId(request.getAgentRowId().trim())
                .ifPresent(rowId -> {
                    try {
                        crmOpenApiService.updateAgentLoginStatus(rowId, status);
                    } catch (Exception ex) {
                        log.warn("CRM status update failed, enqueue async task. agent={}, status={}",
                                request.getAgentRowId().trim(), status, ex);
                    }
                });

        // 3. 如果目标状态是 ONLINE，尝试领取待分配客户（最多 10 个）
        if (target == AgentPresence.ONLINE) {
            int maxAssignments = 10;
            int assigned = 0;
            while (assigned < maxAssignments) {
                try {
                    var pending = agentDispatchService.assignOnePendingCustomerToAgent(request.getAgentRowId().trim());
                    if (pending.isEmpty()) {
                        break;
                    }
                    String customerPhone = pending.get();
                    crmOpenApiService.addAssignmentRecord(customerPhone, request.getAgentRowId().trim(), "服务中");
                    crmOpenApiService.assignAiReception(customerPhone);
                    agentPushService.pushHistory(request.getAgentRowId().trim(), customerPhone, chatHistoryService.listMessages(customerPhone));
                    assigned++;
                } catch (Exception ex) {
                    log.warn("Pending customer assignment failed, continue. agent={}", request.getAgentRowId().trim(), ex);
                }
            }
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "状态更新成功"));
    }

    @GetMapping
    public ResponseEntity<?> listStatus(@RequestParam(value = "agentRowId", required = false) String agentRowId,
                                        @RequestParam(value = "status", required = false) String status,
                                        @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                        @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        var user = CurrentUser.get();
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(agentRowId)) {
            if (!chatPermissionService.canViewAgent(user, agentRowId.trim())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("FORBIDDEN", "无权查看该坐席状态"));
            }
            filters.add(filter(AGENT_ROW_ID_CONTROL_ID, agentRowId.trim(), 29, 1, 24));
        } else if (user.getRole() == AgentRole.MANAGER
                && user.getManagedAgentIds() != null
                && !user.getManagedAgentIds().isEmpty()) {
            // MANAGER: CRM 不支持同一 controlId 的 OR 过滤，不在 CRM 层过滤，在 Java 层后过滤
        } else if (!chatPermissionService.canManageAgentLevels(user)) {
            filters.add(filter(AGENT_ROW_ID_CONTROL_ID, user.getAccountRowId(), 29, 1, 24));
        }
        if (StringUtils.hasText(status)) {
            filters.add(filter(AGENT_STATUS_CONTROL_ID, status.trim(), 11, 1, 2));
        }

        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "查询坐席状态失败"));
        }

        // MANAGER 后过滤：仅返回 managedAgentIds 范围内的坐席
        if (user.getRole() == AgentRole.MANAGER
                && !StringUtils.hasText(agentRowId)
                && user.getManagedAgentIds() != null
                && !user.getManagedAgentIds().isEmpty()) {
            JsonNode rows = root.path("data").path("rows");
            if (rows.isArray()) {
                List<String> managedIds = user.getManagedAgentIds();
                List<JsonNode> filtered = new ArrayList<>();
                for (JsonNode row : rows) {
                    JsonNode relation = row.path(AGENT_ROW_ID_CONTROL_ID);
                    if (relation.isTextual()) {
                        List<String> sids = CrmRelationIds.parseText(relation.asText());
                        if (sids.stream().anyMatch(managedIds::contains)) {
                            filtered.add(row);
                        }
                    }
                }
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "total", filtered.size(),
                        "rows", filtered
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")
        ));
    }

    private Map<String, Object> filter(String controlId, String value, int dataType, int spliceType, int filterType) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("dataType", dataType);
        item.put("spliceType", spliceType);
        item.put("filterType", filterType);
        item.put("value", value);
        return item;
    }
}
