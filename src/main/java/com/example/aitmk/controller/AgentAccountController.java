package com.example.aitmk.controller;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.domain.AgentAccountUpsertRequest;
import com.example.aitmk.model.domain.AgentAccountView;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.security.permission.ChatPermissionService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.support.CrmRelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    private static final String LOGIN_ROLE_CONTROL_ID = "6a322b23cd23604cb463cc07";
    private static final String LOGIN_ENABLED_CONTROL_ID = "6a322b23cd23604cb463cc08";
    private static final String LOGIN_MANAGED_AGENTS_CONTROL_ID = "6a36b886cd23604cb4641e40";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CrmOpenApiService crmOpenApiService;
    private final ChatPermissionService chatPermissionService;

    @PostMapping
    public ResponseEntity<?> addAccount(@Valid @RequestBody AgentAccountUpsertRequest request) {
        ResponseEntity<?> forbidden = requireOwner();
        if (forbidden != null) {
            return forbidden;
        }
        if (!StringUtils.hasText(request.getPassword())) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "新增账号时 password 不能为空");
        }
        List<AgentAccountView> accounts = loadAccounts();
        String role = normalizeRole(request.getRole());
        List<String> managed = validateManagedAgentIds(request.getManagedAgentIds(), role, null, accounts);
        List<Map<String, Object>> controls = buildControls(request, true, role, managed);
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return failure(HttpStatus.BAD_REQUEST, "CRM_WRITE_FAILED", "新增坐席账号失败");
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "rowId", root.path("data").asText("")
        ));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> editAccount(@PathVariable String rowId,
                                         @Valid @RequestBody AgentAccountUpsertRequest request) {
        ResponseEntity<?> forbidden = requireOwner();
        if (forbidden != null) {
            return forbidden;
        }
        if (!StringUtils.hasText(rowId)) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "rowId 不能为空");
        }
        List<AgentAccountView> accounts = loadAccounts();
        AgentAccountView current = accounts.stream().filter(a -> a.rowId().equals(rowId)).findFirst()
                .orElseThrow(() -> new AccountValidationException("ACCOUNT_NOT_FOUND", "账号不存在"));
        String role = StringUtils.hasText(request.getRole()) ? normalizeRole(request.getRole()) : current.role();
        List<String> managed = validateManagedAgentIds(request.getManagedAgentIds(), role, rowId, accounts);
        List<Map<String, Object>> controls = buildControls(request, false, role, managed);
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return failure(HttpStatus.BAD_REQUEST, "CRM_WRITE_FAILED", "修改坐席账号失败");
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> listAccounts(@RequestParam(value = "keyword", required = false) String keyword,
                                          @RequestParam(value = "pageSize", defaultValue = "50") int pageSize,
                                          @RequestParam(value = "pageIndex", defaultValue = "1") int pageIndex) {
        ResponseEntity<?> forbidden = requireOwner();
        if (forbidden != null) {
            return forbidden;
        }
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(keyword)) {
            filters.add(filter(LOGIN_ACCOUNT_CONTROL_ID, keyword.trim(), 2, 1, 7)); // contains
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            return failure(HttpStatus.BAD_REQUEST, "CRM_QUERY_FAILED", "查询坐席账号失败");
        }
        List<AgentAccountView> rows = new ArrayList<>();
        root.path("data").path("rows").forEach(row -> rows.add(accountView(row)));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "total", root.path("data").path("total").asInt(0),
                "rows", rows
        ));
    }

    @GetMapping("/{rowId}")
    public ResponseEntity<?> getAccount(@PathVariable String rowId) {
        ResponseEntity<?> forbidden = requireOwner(); if (forbidden != null) return forbidden;
        AgentAccountView account = loadAccounts().stream().filter(a -> a.rowId().equals(rowId)).findFirst()
                .orElseThrow(() -> new AccountValidationException("ACCOUNT_NOT_FOUND", "账号不存在"));
        return ResponseEntity.ok(Map.of("success", true, "data", account));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> deleteAccount(@PathVariable String rowId) {
        ResponseEntity<?> forbidden = requireOwner();
        if (forbidden != null) {
            return forbidden;
        }
        if (!StringUtils.hasText(rowId)) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "rowId 不能为空");
        }
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            return failure(HttpStatus.BAD_REQUEST, "CRM_WRITE_FAILED", "删除坐席账号失败");
        }
        return ResponseEntity.ok(Map.of("success", true));
    }

    private List<Map<String, Object>> buildControls(AgentAccountUpsertRequest request, boolean includePassword,
                                                     String effectiveRole, List<String> managedAgentIds) {
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
        if (StringUtils.hasText(request.getRole())) {
            controls.add(selectControl(LOGIN_ROLE_CONTROL_ID, effectiveRole));
        }
        if (StringUtils.hasText(request.getEnabled())) {
            controls.add(selectControl(LOGIN_ENABLED_CONTROL_ID, normalizeEnabled(request.getEnabled())));
        }
        // Relation(type=29) 的接口值是逗号字符串；始终写入，确保保存为全量覆盖，非 MANAGER 写空串。
        controls.add(control(LOGIN_MANAGED_AGENTS_CONTROL_ID,
                "MANAGER".equals(effectiveRole) ? CrmRelationIds.serialize(managedAgentIds) : ""));
        return controls;
    }

    private List<String> validateManagedAgentIds(List<String> rawIds, String role, String selfRowId, List<AgentAccountView> accounts) {
        if (!"MANAGER".equals(role)) return List.of();
        List<String> ids = CrmRelationIds.parseText(CrmRelationIds.serialize(rawIds));
        Map<String,AgentAccountView> byId = accounts.stream().collect(java.util.stream.Collectors.toMap(AgentAccountView::rowId, a -> a));
        for (String id : ids) {
            if (id.equals(selfRowId)) throw new AccountValidationException("MANAGED_AGENT_SELF", "不能将当前 MANAGER 加入自己的管理范围");
            AgentAccountView target = byId.get(id);
            if (target == null) throw new AccountValidationException("MANAGED_AGENT_INVALID", "管理范围包含不存在的账号");
            if (!"TMK".equals(target.role()) || !target.enabled())
                throw new AccountValidationException("MANAGED_AGENT_INVALID", "管理范围仅允许选择启用的 TMK 账号");
        }
        return ids;
    }

    private List<AgentAccountView> loadAccounts() {
        List<AgentAccountView> result = new ArrayList<>(); int page = 1; int total;
        do {
            JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, List.of(), 200, page++, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false))
                throw new AccountValidationException("CRM_QUERY_FAILED", "无法校验管理范围");
            total = root.path("data").path("total").asInt(0);
            JsonNode rows = root.path("data").path("rows");
            for (JsonNode row : rows) {
                String id = row.path("rowid").asText("").trim();
                if (!id.isBlank()) result.add(accountView(row));
            }
            if (!rows.isArray() || rows.isEmpty()) break;
        } while (result.size() < total);
        return result;
    }

    private AgentAccountView accountView(JsonNode row) {
        return new AgentAccountView(row.path("rowid").asText("").trim(), text(row, LOGIN_ACCOUNT_CONTROL_ID),
                text(row, LOGIN_RELATED_USER_CONTROL_ID), text(row, LOGIN_AGENT_LEVEL_CONTROL_ID),
                normalizeRole(text(row, LOGIN_ROLE_CONTROL_ID)), normalizeEnabled(text(row, LOGIN_ENABLED_CONTROL_ID)).equals("启用"),
                CrmRelationIds.parse(row.get(LOGIN_MANAGED_AGENTS_CONTROL_ID)));
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.path(field); if (value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isArray() && !value.isEmpty()) {
            JsonNode first = value.get(0);
            for (String key : List.of("sid","id","name")) if (first.has(key)) return first.path(key).asText("");
        }
        return value.asText("");
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AccountValidationException.class)
    ResponseEntity<V2Api.Failure> accountValidation(AccountValidationException ex) {
        HttpStatus status = "ACCOUNT_NOT_FOUND".equals(ex.code) ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(new V2Api.Failure(new V2Api.Error(ex.code, ex.getMessage(), null)));
    }

    private static final class AccountValidationException extends RuntimeException {
        private final String code; private AccountValidationException(String code,String message){super(message);this.code=code;}
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

    private Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
    }

    private Map<String, Object> selectControl(String controlId, String value) {
        Map<String, Object> item = control(controlId, value);
        item.put("valueType", 2);
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

    private String normalizeRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase();
        if ("OWNER".equals(role) || "MANAGER".equals(role) || "TMK".equals(role)) {
            return role;
        }
        return "TMK";
    }

    private String normalizeEnabled(String raw) {
        String status = raw == null ? "" : raw.trim();
        if ("停用".equals(status) || "false".equalsIgnoreCase(status) || "0".equals(status)) {
            return "停用";
        }
        return "启用";
    }

    private ResponseEntity<?> requireOwner() {
        if (chatPermissionService.canManageAccounts(CurrentUser.get())) {
            return null;
        }
        return failure(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅 OWNER 可管理坐席账号");
    }

    private ResponseEntity<V2Api.Failure> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new V2Api.Failure(new V2Api.Error(code, message, null)));
    }
}
