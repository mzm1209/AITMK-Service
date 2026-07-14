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
 * Activity options for the leads_bank activity relation field.
 */
@RestController
@RequestMapping("/api/leads")
@RequiredArgsConstructor
public class LeadActivityController {

    private static final String CONTENT_WORKSHEET_ID = "68c2460eb75138cd755fb461";
    private static final String CONTENT_NAME = "68c2460eb75138cd755fb462";
    private static final String CONTENT_TIPS = "68c2460eb75138cd755fb463";
    private static final int MAX_PAGE_SIZE = 200;

    private final CrmOpenApiService crmOpenApiService;

    @GetMapping("/activities")
    public ResponseEntity<?> activities(@RequestParam(value = "keyword", required = false) String keyword,
                                        @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                        @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        int safePageSize = Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int safePageIndex = Math.max(1, pageIndex);
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            filters.add(filter(CONTENT_NAME, keyword.trim(), 2, 1, 7));
        }

        JsonNode root = crmOpenApiService.frontendGetFilterRows(
                CONTENT_WORKSHEET_ID, filters, safePageSize, safePageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "查询活动列表失败",
                    "crmResponse", root == null ? Map.of() : root
            ));
        }

        JsonNode data = root.path("data");
        JsonNode rows = data.path("rows");
        List<Map<String, Object>> resultRows = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                String rowId = row.path("rowid").asText("");
                if (!StringUtils.hasText(rowId)) {
                    continue;
                }
                resultRows.add(Map.of(
                        "rowId", rowId,
                        "name", extractText(row, CONTENT_NAME),
                        "tips", extractText(row, CONTENT_TIPS)
                ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", data.path("total").asInt(resultRows.size()),
                "rows", resultRows
        ));
    }

    private static Map<String, Object> filter(String controlId, String value,
                                              int dataType, int spliceType, int filterType) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("dataType", dataType);
        item.put("spliceType", spliceType);
        item.put("filterType", filterType);
        item.put("value", value);
        return item;
    }

    private static String extractText(JsonNode row, String controlId) {
        JsonNode node = row.get(controlId);
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.has("name")) return first.path("name").asText("");
            if (first.has("value")) return first.path("value").asText("");
            return first.asText("");
        }
        return node.asText("");
    }
}
