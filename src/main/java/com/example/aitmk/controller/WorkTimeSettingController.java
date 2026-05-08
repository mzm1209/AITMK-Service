package com.example.aitmk.controller;

import com.example.aitmk.model.domain.WorkTimeSettingUpsertRequest;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.WorkTimeSettingCacheService;
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
 * 工作时间设置管理接口。
 */
@RestController
@RequestMapping("/api/work-time-settings")
@RequiredArgsConstructor
public class WorkTimeSettingController {

    private static final String WORKSHEET_ID = "69fd6e76cd23604cb45f0950";
    private static final String START_TIME_CONTROL_ID = "69fd6fc2cd23604cb45f095d";
    private static final String END_TIME_CONTROL_ID = "69fd7074cd23604cb45f0969";

    private final CrmOpenApiService crmOpenApiService;
    private final WorkTimeSettingCacheService cacheService;

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody WorkTimeSettingUpsertRequest request) {
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "新增工作时间设置失败"));
        }
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true, "rowId", root.path("data").asText("")));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> edit(@PathVariable String rowId, @Valid @RequestBody WorkTimeSettingUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "修改工作时间设置失败"));
        }
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> delete(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "删除工作时间设置失败"));
        }
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                  @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, List.of(), pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "查询工作时间设置失败"));
        }
        return ResponseEntity.ok(Map.of("success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")));
    }

    @GetMapping("/cache")
    public ResponseEntity<?> cacheSnapshot() {
        return ResponseEntity.ok(Map.of("success", true, "rows", cacheService.snapshot()));
    }

    private List<Map<String, Object>> buildControls(WorkTimeSettingUpsertRequest request) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(START_TIME_CONTROL_ID, request.getStartTime()));
        controls.add(control(END_TIME_CONTROL_ID, request.getEndTime()));
        return controls;
    }

    private Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
    }
}
