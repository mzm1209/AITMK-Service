package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentAccountUpsertRequest;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * IM Web 坐席账号管理接口。
 */
@RestController
@RequestMapping("/api/agent/accounts")
@RequiredArgsConstructor
public class AgentAccountController {

    private static final String WORKSHEET_ID = "imzhgl";
    private static final String LOGIN_ACCOUNT_CONTROL_ID = "69abab83433ec9f4b5e6ce0e";
    private static final String LOGIN_PASSWORD_CONTROL_ID = "69abacc3433ec9f4b5e6ce25";
    private static final String LOGIN_RELATED_USER_CONTROL_ID = "69abacc3433ec9f4b5e6ce26";
    private static final String LOGIN_AGENT_LEVEL_CONTROL_ID = "69ca5415433ec9f4b5e7fced";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CrmOpenApiService crmOpenApiService;

    @PostMapping
    public ResponseEntity<?> addAccount(@Valid @RequestBody AgentAccountUpsertRequest request) {
        if (!StringUtils.hasText(request.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "新增账号时 password 不能为空"));
        }
        List<Map<String, Object>> controls = buildControls(request, true);
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("新增坐席账号失败", root));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "rowId", root.path("data").asText("")
        ));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> editAccount(@PathVariable String rowId,
                                         @Valid @RequestBody AgentAccountUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        List<Map<String, Object>> controls = buildControls(request, false);
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("修改坐席账号失败", root));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> listAccounts(@RequestParam(value = "keyword", required = false) String keyword,
                                          @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                          @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            filters.add(filter(LOGIN_ACCOUNT_CONTROL_ID, keyword.trim(), 2, 1, 7)); // contains
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("查询坐席账号失败", root));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", root.path("data").path("rows")
        ));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> deleteAccount(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        }
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return ResponseEntity.badRequest().body(failBody("删除坐席账号失败", root));
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private List<Map<String, Object>> buildControls(AgentAccountUpsertRequest request, boolean includePassword) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(LOGIN_ACCOUNT_CONTROL_ID, request.getLoginAccount()));
        if (includePassword || StringUtils.hasText(request.getPassword())) {
            controls.add(control(LOGIN_PASSWORD_CONTROL_ID, request.getPassword()));
        }
        if (StringUtils.hasText(request.getRelatedUserIds())) {
            controls.add(control(LOGIN_RELATED_USER_CONTROL_ID, normalizeRelationIds(request.getRelatedUserIds())));
        }
        if (request.getAgentLevel() != null) {
            // 关联记录字段（dataType=29）按字符串逗号分隔 rowId，全量覆盖
            controls.add(control(LOGIN_AGENT_LEVEL_CONTROL_ID, normalizeRelationIds(request.getAgentLevel())));
        }
        return controls;
    }

    /**
     * 兼容三种前端传法：
     * 1) 纯 rowId/accountId 或逗号分隔
     * 2) JSON 数组字符串：[{"accountId":"..."}, {"sid":"..."}]
     * 3) JSON 对象字符串：{"accountId":"..."} / {"sid":"..."} / {"rowid":"..."}
     */
    private String normalizeRelationIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.trim();
        if (!(text.startsWith("[") || text.startsWith("{"))) {
            return text;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(text);
            List<String> ids = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(node -> {
                    String id = extractRelationId(node);
                    if (StringUtils.hasText(id)) {
                        ids.add(id);
                    }
                });
            } else if (root.isObject()) {
                String id = extractRelationId(root);
                if (StringUtils.hasText(id)) {
                    ids.add(id);
                }
            }
            return ids.isEmpty() ? text : String.join(",", ids);
        } catch (Exception e) {
            return text;
        }
    }

    private String extractRelationId(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.has("accountId")) {
            return node.path("accountId").asText("");
        }
        if (node.has("sid")) {
            return node.path("sid").asText("");
        }
        if (node.has("rowid")) {
            return node.path("rowid").asText("");
        }
        if (node.has("id")) {
            return node.path("id").asText("");
        }
        return "";
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
