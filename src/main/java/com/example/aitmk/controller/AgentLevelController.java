package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentLevelUpsertRequest;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IM Web 坐席等级管理接口（zxdjgl）。
 */
@RestController
@RequestMapping("/api/agent/levels")
@RequiredArgsConstructor
public class AgentLevelController {

    private static final String WORKSHEET_ID = "zxdjgl";
    private static final String LEVEL_NAME_CONTROL_ID = "69ca5008433ec9f4b5e7fc14";
    private static final String LEVEL_WEIGHT_CONTROL_ID = "69ca5263433ec9f4b5e7fc9d";
    private static final String LEVEL_MAX_LOAD_CONTROL_ID = "69ca5313433ec9f4b5e7fcab";

    private final CrmOpenApiService crmOpenApiService;

    @PostMapping
    public ResponseEntity<?> addLevel(@Valid @RequestBody AgentLevelUpsertRequest request) {
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "新增坐席等级失败"));
        }
        return ResponseEntity.ok(Map.of("success", true, "rowId", root.path("data").asText("")));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> editLevel(@PathVariable String rowId,
                                       @Valid @RequestBody AgentLevelUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "修改坐席等级失败"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> listLevels(@RequestParam(value = "keyword", required = false) String keyword,
                                        @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                        @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            filters.add(filter(LEVEL_NAME_CONTROL_ID, keyword.trim(), 2, 1, 7));
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "查询坐席等级失败"));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")
        ));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> deleteLevel(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "删除坐席等级失败"));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private List<Map<String, Object>> buildControls(AgentLevelUpsertRequest request) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(LEVEL_NAME_CONTROL_ID, request.getLevelName()));
        controls.add(control(LEVEL_WEIGHT_CONTROL_ID, request.getWeight()));
        controls.add(control(LEVEL_MAX_LOAD_CONTROL_ID, request.getMaxLoad()));
        return controls;
    }

    private Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
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
