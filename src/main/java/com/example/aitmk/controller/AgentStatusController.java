package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentStatusUpdateRequest;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.AgentPushService;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
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


    @PostMapping("/update")
    public ResponseEntity<?> updateStatus(@Valid @RequestBody AgentStatusUpdateRequest request) {
        String status = request.getStatus().trim();
        if (!"在线".equals(status) && !"挂机".equals(status) && !"离线".equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "仅支持 在线/挂机/离线"));
        }

        // 查最新可用登录记录，更新CRM状态
        crmOpenApiService.findActiveLoginRecordRowId(request.getAgentRowId().trim())
                .ifPresent(rowId -> crmOpenApiService.updateAgentLoginStatus(rowId, status));

        if ("在线".equals(status)) {
            agentDispatchService.markOnline(request.getAgentRowId().trim());
            while (true) {
                var pending = agentDispatchService.assignOnePendingCustomerToAgent(request.getAgentRowId().trim());
                if (pending.isEmpty()) {
                    break;
                }
                String customerPhone = pending.get();
                crmOpenApiService.addAssignmentRecord(customerPhone, request.getAgentRowId().trim(), "服务中");
                crmOpenApiService.assignAiReception(customerPhone);
                agentPushService.pushHistory(request.getAgentRowId().trim(), customerPhone, chatHistoryService.listMessages(customerPhone));
            }
        } else {
            agentDispatchService.markOffline(request.getAgentRowId().trim());
        }

        return ResponseEntity.ok(Map.of("success", true, "message", "状态更新成功"));
    }

    @GetMapping
    public ResponseEntity<?> listStatus(@RequestParam(value = "agentRowId", required = false) String agentRowId,
                                        @RequestParam(value = "status", required = false) String status,
                                        @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                        @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(agentRowId)) {
            filters.add(filter(AGENT_ROW_ID_CONTROL_ID, agentRowId.trim(), 29, 1, 24));
        }
        if (StringUtils.hasText(status)) {
            filters.add(filter(AGENT_STATUS_CONTROL_ID, status.trim(), 11, 1, 2));
        }

        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "查询坐席状态失败"));
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
