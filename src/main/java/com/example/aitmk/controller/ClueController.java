package com.example.aitmk.controller;

import com.example.aitmk.model.domain.ClueQueryRequest;
import com.example.aitmk.model.domain.ClueUpsertRequest;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 线索管理表（CRM）CRUD 接口，供 IM Web 使用。
 */
@RestController
@RequestMapping("/api/leads/clues")
@RequiredArgsConstructor
public class ClueController {

    private final CrmOpenApiService crmOpenApiService;

    /**
     * 默认使用线索管理表 worksheetId，可通过配置覆盖。
     */
    @Value("${crm.clue.worksheet-id:ae12fbae-21ed-4497-946f-d78e62fcb928}")
    private String clueWorksheetId;

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody ClueUpsertRequest request) {
        JsonNode root = crmOpenApiService.frontendAddRow(
                clueWorksheetId,
                request.getControls(),
                request.getTriggerWorkflow() == null || request.getTriggerWorkflow()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("新增线索失败", root));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "rowId", root.path("data").asText("")
        ));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> edit(@PathVariable String rowId,
                                  @Valid @RequestBody ClueUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendEditRow(
                clueWorksheetId,
                rowId,
                request.getControls(),
                request.getTriggerWorkflow() == null || request.getTriggerWorkflow()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("修改线索失败", root));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "keyword", required = false) String keyword,
                                  @RequestParam(value = "keywordControlId", required = false) String keywordControlId,
                                  @RequestParam(value = "keywordDataType", defaultValue = "2") int keywordDataType,
                                  @RequestParam(value = "keywordFilterType", defaultValue = "7") int keywordFilterType,
                                  @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                  @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        List<Map<String, Object>> filters = new java.util.ArrayList<>();
        if (StringUtils.hasText(keyword) && StringUtils.hasText(keywordControlId)) {
            filters.add(filter(keywordControlId.trim(), keyword.trim(), keywordDataType, 1, keywordFilterType));
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(clueWorksheetId, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("查询线索失败", root));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")
        ));
    }

    @PostMapping("/query")
    public ResponseEntity<?> query(@RequestBody ClueQueryRequest request) {
        JsonNode root = crmOpenApiService.frontendGetFilterRows(
                clueWorksheetId,
                request.getFilters(),
                request.getPageSize(),
                request.getPageIndex(),
                request.getListType(),
                request.getSortControls()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("查询线索失败", root));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")
        ));
    }

    @GetMapping("/{rowId}")
    public ResponseEntity<?> detail(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(
                clueWorksheetId,
                List.of(filter("rowid", rowId.trim(), 2, 1, 2)),
                1,
                1,
                0,
                List.of()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("查询线索详情失败", root));
        }
        JsonNode rows = root.path("data").path("rows");
        JsonNode item = rows.isArray() && rows.size() > 0 ? rows.get(0) : null;
        return ResponseEntity.ok(Map.of(
                "success", true,
                "row", item
        ));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> delete(@PathVariable String rowId,
                                    @RequestParam(value = "triggerWorkflow", defaultValue = "true") boolean triggerWorkflow) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendDeleteRow(clueWorksheetId, rowId, triggerWorkflow);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("删除线索失败", root));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private Map<String, Object> failBody(String message, JsonNode root) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", message);
        if (root != null && !root.isMissingNode()) {
            body.put("crmResponse", root);
        }
        return body;
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
