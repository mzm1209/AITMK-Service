package com.example.aitmk.controller;

import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
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
