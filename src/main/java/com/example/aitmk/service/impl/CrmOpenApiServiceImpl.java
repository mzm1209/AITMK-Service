package com.example.aitmk.service.impl;

import com.example.aitmk.config.CrmConfig;
import com.example.aitmk.model.domain.AssignmentRecord;
import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.domain.CrmChatRecord;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrmOpenApiServiceImpl implements CrmOpenApiService {

    private static final String LOGIN_WORKSHEET_ID = "imzhgl";
    private static final String LOGIN_ACCOUNT_CONTROL_ID = "69abab83433ec9f4b5e6ce0e";
    private static final String LOGIN_PASSWORD_CONTROL_ID = "69abacc3433ec9f4b5e6ce25";
    private static final String LOGIN_RELATED_USER_CONTROL_ID = "69abacc3433ec9f4b5e6ce26";

    private static final String AGENT_LOGIN_WORKSHEET_ID = "zxzt";
    private static final String AGENT_LOGIN_ACCOUNT_CONTROL_ID = "69aea988433ec9f4b5e70086";
    private static final String AGENT_LOGIN_STATUS_CONTROL_ID = "69abbb3e433ec9f4b5e6d085";
    private static final String AGENT_LOGIN_TIME_CONTROL_ID = "69abbb3e433ec9f4b5e6d086";

    private static final String ASSIGNMENT_WORKSHEET_ID = "ltjl";
    private static final String ASSIGN_CUSTOMER_PHONE_CONTROL_ID = "69abb3a0433ec9f4b5e6cff4";
    private static final String ASSIGN_AGENT_CONTROL_ID = "69abbcaf433ec9f4b5e6d0f6";
    private static final String ASSIGN_TIME_CONTROL_ID = "69abb8d7433ec9f4b5e6d05f";
    private static final String ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID = "69abb984433ec9f4b5e6d069";
    private static final String ASSIGN_SERVICE_STATUS_CONTROL_ID = "69abba17433ec9f4b5e6d06e";
    private static final String ASSIGN_REPLYABLE_CONTROL_ID = "69d4b066433ec9f4b5e86d1d";

    private static final String CHAT_WORKSHEET_ID = "ltjl1";
    private static final String CHAT_BUSINESS_ACCOUNT_CONTROL_ID = "69abbccf433ec9f4b5e6d0fe";
    private static final String CHAT_CUSTOMER_PHONE_CONTROL_ID = "69abbd3b433ec9f4b5e6d108";
    private static final String CHAT_AGENT_CONTROL_ID = "69abbd3b433ec9f4b5e6d109";
    private static final String CHAT_SENDER_CONTROL_ID = "69abbfff433ec9f4b5e6d226";
    private static final String CHAT_SEND_TIME_CONTROL_ID = "69abbfff433ec9f4b5e6d227";
    private static final String CHAT_CONTENT_CONTROL_ID = "69abbfff433ec9f4b5e6d228";
    private static final String AI_POOL_WORKSHEET_ID = "aijdc";
    private static final String AI_POOL_CUSTOMER_PHONE_CONTROL_ID = "69cb3e3b433ec9f4b5e80433";
    private static final String AI_POOL_ASSIGN_TIME_CONTROL_ID = "69cb3ff3433ec9f4b5e80476";
    private static final String AI_POOL_REASON_CONTROL_ID = "69cb3ff3433ec9f4b5e80477";
    private static final String AI_POOL_STATUS_CONTROL_ID = "69cb3ff3433ec9f4b5e80478";
    private static final String AI_POOL_TRANSFER_TIME_CONTROL_ID = "69cb3ff3433ec9f4b5e80479";

    private static final DateTimeFormatter CRM_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");

    private final CrmConfig crmConfig;
    private final ObjectMapper objectMapper;

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public Optional<CrmAgentAccount> verifyLogin(String username, String password) {
        try {
            List<Map<String, Object>> filters = new ArrayList<>();
            filters.add(filter(LOGIN_ACCOUNT_CONTROL_ID, username,2,1,2));
            filters.add(filter(LOGIN_PASSWORD_CONTROL_ID, password,2,1,2));

            JsonNode root = getFilterRows(LOGIN_WORKSHEET_ID, filters, 50);
            if (root == null || !root.path("success").asBoolean(false)) {
                return Optional.empty();
            }

            int total = root.path("data").path("total").asInt(0);
            if (total <= 0) {
                return Optional.empty();
            }

            JsonNode first = firstRow(root);
            if (first == null) {
                return Optional.empty();
            }

            return Optional.of(CrmAgentAccount.builder()
                    .rowId(first.path("rowid").asText())
                    .loginAccount(extractAsText(first, LOGIN_ACCOUNT_CONTROL_ID))
                    .relatedUserIds(extractAsText(first, LOGIN_RELATED_USER_CONTROL_ID))
                    .build());
        } catch (Exception e) {
            log.error("CRM verifyLogin failed", e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> findOnlineLoginRecordRowId(String agentAccountRowId) {
        List<Map<String, Object>> filters = new ArrayList<>();
        filters.add(filter(AGENT_LOGIN_ACCOUNT_CONTROL_ID, agentAccountRowId,29,1,24));
        filters.add(filter(AGENT_LOGIN_STATUS_CONTROL_ID, "在线",11,1,2));

        JsonNode root = getFilterRows(AGENT_LOGIN_WORKSHEET_ID, filters, 1);
        if (root == null || !root.path("success").asBoolean(false)) {
            return Optional.empty();
        }

        JsonNode first = firstRow(root);
        if (first == null) {
            return Optional.empty();
        }

        String rowId = first.path("rowid").asText("");
        return rowId.isBlank() ? Optional.empty() : Optional.of(rowId);
    }

    @Override
    public Optional<String> addAgentLoginRecord(String agentAccountRowId, String status) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(AGENT_LOGIN_ACCOUNT_CONTROL_ID, agentAccountRowId));
        controls.add(selectControl(AGENT_LOGIN_STATUS_CONTROL_ID, status));
        controls.add(control(AGENT_LOGIN_TIME_CONTROL_ID, now()));

        JsonNode root = addRow(AGENT_LOGIN_WORKSHEET_ID, controls);
        if (root == null || !root.path("success").asBoolean(false)) {
            return Optional.empty();
        }
        String rowId = root.path("data").asText("");
        return rowId.isBlank() ? Optional.empty() : Optional.of(rowId);
    }

    @Override
    public boolean updateAgentLoginStatus(String loginRecordRowId, String status) {
        if (loginRecordRowId == null || loginRecordRowId.isBlank()) {
            return false;
        }

        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(selectControl(AGENT_LOGIN_STATUS_CONTROL_ID, status));
        controls.add(control(AGENT_LOGIN_TIME_CONTROL_ID, now()));

        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", AGENT_LOGIN_WORKSHEET_ID);
        body.put("rowId", loginRecordRowId);
        body.put("triggerWorkflow", true);
        body.put("controls", controls);

        JsonNode root = post("/api/v2/open/worksheet/editRow", body);
        return root != null && root.path("success").asBoolean(false);
    }

    @Override
    public boolean addAssignmentRecord(String customerPhone, String agentAccountRowId, String serviceStatus) {
        String normalizedAgentRowId = normalizeRelationRowId(agentAccountRowId);
        if (normalizedAgentRowId == null || normalizedAgentRowId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(ASSIGN_CUSTOMER_PHONE_CONTROL_ID, customerPhone));
        controls.add(control(ASSIGN_AGENT_CONTROL_ID, normalizedAgentRowId));
        controls.add(control(ASSIGN_TIME_CONTROL_ID, now()));
        controls.add(control(ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID, now()));
        controls.add(selectControl(ASSIGN_SERVICE_STATUS_CONTROL_ID, serviceStatus));
        controls.add(selectControl(ASSIGN_REPLYABLE_CONTROL_ID, "是"));
        JsonNode root = addRow(ASSIGNMENT_WORKSHEET_ID, controls);
        return root != null && root.path("success").asBoolean(false);
    }

    @Override
    public boolean closeServingAssignment(String customerPhone) {
        List<Map<String, Object>> filters = List.of(
                filter(ASSIGN_CUSTOMER_PHONE_CONTROL_ID, customerPhone, 2, 1, 2),
                filter(ASSIGN_SERVICE_STATUS_CONTROL_ID, "服务中", 11, 1, 2)
        );
        JsonNode root = getFilterRows(ASSIGNMENT_WORKSHEET_ID, filters, 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return false;
        }

        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return false;
        }

        JsonNode target = null;
        Instant latest = Instant.EPOCH;
        for (JsonNode row : rows) {
            Instant t = parseCrmTime(extractAsText(row, ASSIGN_TIME_CONTROL_ID));
            if (target == null || t.isAfter(latest)) {
                target = row;
                latest = t;
            }
        }

        if (target == null) {
            return false;
        }

        String rowId = target.path("rowid").asText("");
        if (rowId.isBlank()) {
            return false;
        }

        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(selectControl(ASSIGN_SERVICE_STATUS_CONTROL_ID, "已关闭"));
        controls.add(control(ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID, now()));

        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", ASSIGNMENT_WORKSHEET_ID);
        body.put("rowId", rowId);
        body.put("triggerWorkflow", true);
        body.put("controls", controls);

        JsonNode edit = post("/api/v2/open/worksheet/editRow", body);
        return edit != null && edit.path("success").asBoolean(false);
    }

    @Override
    public boolean updateServingAssignmentReplyable(String customerPhone, boolean replyable) {
        List<Map<String, Object>> filters = List.of(
                filter(ASSIGN_CUSTOMER_PHONE_CONTROL_ID, customerPhone, 2, 1, 2),
                filter(ASSIGN_SERVICE_STATUS_CONTROL_ID, "服务中", 11, 1, 2)
        );
        JsonNode root = getFilterRows(ASSIGNMENT_WORKSHEET_ID, filters, 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return false;
        }
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return false;
        }
        JsonNode target = null;
        Instant latest = Instant.EPOCH;
        for (JsonNode row : rows) {
            Instant t = parseCrmTime(extractAsText(row, ASSIGN_TIME_CONTROL_ID));
            if (target == null || t.isAfter(latest)) {
                target = row;
                latest = t;
            }
        }
        if (target == null) {
            return false;
        }
        String rowId = target.path("rowid").asText("");
        if (rowId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(selectControl(ASSIGN_REPLYABLE_CONTROL_ID, replyable ? "是" : "否"));
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", ASSIGNMENT_WORKSHEET_ID);
        body.put("rowId", rowId);
        body.put("triggerWorkflow", true);
        body.put("controls", controls);
        JsonNode edit = post("/api/v2/open/worksheet/editRow", body);
        return edit != null && edit.path("success").asBoolean(false);
    }

    @Override
    public boolean addChatRecord(String businessAccountId,
                                 String customerPhone,
                                 String agentAccountRowId,
                                 String sender,
                                 String message) {
        String normalizedAgentRowId = normalizeRelationRowId(agentAccountRowId);
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(CHAT_BUSINESS_ACCOUNT_CONTROL_ID, businessAccountId));
        controls.add(control(CHAT_CUSTOMER_PHONE_CONTROL_ID, customerPhone));
        if (normalizedAgentRowId != null && !normalizedAgentRowId.isBlank()) {
            controls.add(control(CHAT_AGENT_CONTROL_ID, normalizedAgentRowId));
        }
        controls.add(selectControl(CHAT_SENDER_CONTROL_ID, sender));
        controls.add(control(CHAT_SEND_TIME_CONTROL_ID, now()));
        controls.add(control(CHAT_CONTENT_CONTROL_ID, message));
        JsonNode root = addRow(CHAT_WORKSHEET_ID, controls);
        return root != null && root.path("success").asBoolean(false);
    }

    @Override
    public boolean openAiReception(String customerPhone, String reason) {
        if (customerPhone == null || customerPhone.isBlank()) {
            return false;
        }
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(AI_POOL_CUSTOMER_PHONE_CONTROL_ID, customerPhone));
        controls.add(control(AI_POOL_ASSIGN_TIME_CONTROL_ID, now()));
        controls.add(selectControl(AI_POOL_REASON_CONTROL_ID, reason == null || reason.isBlank() ? "首次会话" : reason));
        controls.add(selectControl(AI_POOL_STATUS_CONTROL_ID, "服务中"));
        JsonNode root = addRow(AI_POOL_WORKSHEET_ID, controls);
        return root != null && root.path("success").asBoolean(false);
    }

    @Override
    public boolean closeAiReception(String customerPhone) {
        if (customerPhone == null || customerPhone.isBlank()) {
            return false;
        }
        List<Map<String, Object>> filters = List.of(
                filter(AI_POOL_CUSTOMER_PHONE_CONTROL_ID, customerPhone, 2, 1, 2),
                filter(AI_POOL_STATUS_CONTROL_ID, "服务中", 11, 1, 2)
        );
        JsonNode root = getFilterRows(AI_POOL_WORKSHEET_ID, filters, 1);
        if (root == null || !root.path("success").asBoolean(false)) {
            return false;
        }
        JsonNode row = firstRow(root);
        if (row == null) {
            return false;
        }
        String rowId = row.path("rowid").asText("");
        if (rowId.isBlank()) {
            return false;
        }
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(selectControl(AI_POOL_STATUS_CONTROL_ID, "已关闭"));
        controls.add(control(AI_POOL_TRANSFER_TIME_CONTROL_ID, now()));
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", AI_POOL_WORKSHEET_ID);
        body.put("rowId", rowId);
        body.put("triggerWorkflow", true);
        body.put("controls", controls);
        JsonNode edit = post("/api/v2/open/worksheet/editRow", body);
        return edit != null && edit.path("success").asBoolean(false);
    }


    @Override
    public JsonNode frontendAddRow(String worksheetId, List<Map<String, Object>> controls, boolean triggerWorkflow) {
        if (worksheetId == null || worksheetId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> allControls = new ArrayList<>(controls == null ? List.of() : controls);
        if (crmConfig.getOwnerId() != null && !crmConfig.getOwnerId().isBlank()) {
            allControls.add(control("ownerid", crmConfig.getOwnerId()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("triggerWorkflow", triggerWorkflow);
        body.put("controls", allControls);
        return post("/api/v2/open/worksheet/addRow", body);
    }

    @Override
    public JsonNode frontendGetFilterRows(String worksheetId,
                                          List<Map<String, Object>> filters,
                                          int pageSize,
                                          int pageIndex,
                                          int listType,
                                          List<Map<String, Object>> sortControls) {
        if (worksheetId == null || worksheetId.isBlank()) {
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("pageSize", pageSize <= 0 ? 50 : pageSize);
        body.put("pageIndex", pageIndex <= 0 ? 1 : pageIndex);
        body.put("listType", listType);
        body.put("controls", sortControls == null ? List.of() : sortControls);
        body.put("filters", filters == null ? List.of() : filters);
        return post("/api/v2/open/worksheet/getFilterRows", body);
    }

    @Override
    public JsonNode frontendEditRow(String worksheetId, String rowId, List<Map<String, Object>> controls, boolean triggerWorkflow) {
        if (worksheetId == null || worksheetId.isBlank() || rowId == null || rowId.isBlank()) {
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("rowId", rowId);
        body.put("triggerWorkflow", triggerWorkflow);
        body.put("controls", controls == null ? List.of() : controls);
        return post("/api/v2/open/worksheet/editRow", body);
    }

    @Override
    public JsonNode frontendDeleteRow(String worksheetId, String rowId, boolean triggerWorkflow) {
        if (worksheetId == null || worksheetId.isBlank() || rowId == null || rowId.isBlank()) {
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("rowId", rowId);
        body.put("triggerWorkflow", triggerWorkflow);
        return post("/api/v2/open/worksheet/deleteRow", body);
    }

    @Override
    public List<AssignmentRecord> listAssignments() {
        JsonNode root = getFilterRows(ASSIGNMENT_WORKSHEET_ID,
                List.of(filter(ASSIGN_SERVICE_STATUS_CONTROL_ID, "服务中",11,1,2)), 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return List.of();
        }

        List<AssignmentRecord> result = new ArrayList<>();
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray()) {
            return List.of();
        }

        rows.forEach(row -> {
            String customerPhone = extractAsText(row, ASSIGN_CUSTOMER_PHONE_CONTROL_ID);
            String agent = extractAsText(row, ASSIGN_AGENT_CONTROL_ID);
            if (!customerPhone.isBlank() && !agent.isBlank()) {
                result.add(AssignmentRecord.builder().customerPhone(customerPhone).agentRowId(agent).build());
            }
        });
        return result;
    }

    @Override
    public List<CrmChatRecord> listChatRecords() {
        JsonNode root = getFilterRows(CHAT_WORKSHEET_ID, List.of(), 1000);
        if (root == null || !root.path("success").asBoolean(false)) {
            return List.of();
        }

        List<CrmChatRecord> result = new ArrayList<>();
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray()) {
            return List.of();
        }

        rows.forEach(row -> {
            String customer = extractAsText(row, CHAT_CUSTOMER_PHONE_CONTROL_ID);
            if (customer.isBlank()) {
                return;
            }
            result.add(CrmChatRecord.builder()
                    .businessAccountId(extractAsText(row, CHAT_BUSINESS_ACCOUNT_CONTROL_ID))
                    .customerPhone(customer)
                    .agentRowId(extractAsText(row, CHAT_AGENT_CONTROL_ID))
                    .sender(normalizeSender(extractAsText(row, CHAT_SENDER_CONTROL_ID)))
                    .content(extractAsText(row, CHAT_CONTENT_CONTROL_ID))
                    .sendTime(parseCrmTime(extractAsText(row, CHAT_SEND_TIME_CONTROL_ID)))
                    .build());
        });

        return result;
    }


    @Override
    public Map<String, String> listAgentCustomerServiceStatus(String agentAccountRowId) {
        List<Map<String, Object>> filters = List.of(
                filter(ASSIGN_AGENT_CONTROL_ID, agentAccountRowId,29,1,24)
        );
        JsonNode root = getFilterRows(ASSIGNMENT_WORKSHEET_ID, filters, 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return Map.of();
        }

        Map<String, String> statusMap = new HashMap<>();
        JsonNode rows = root.path("data").path("rows");
        if (rows.isArray()) {
            rows.forEach(row -> {
                String customerPhone = extractAsText(row, ASSIGN_CUSTOMER_PHONE_CONTROL_ID);
                String status = extractAsText(row, ASSIGN_SERVICE_STATUS_CONTROL_ID);
                if (customerPhone.isBlank()) {
                    return;
                }
                // 若同一客户存在多条记录，优先保留“服务中”
                String existing = statusMap.get(customerPhone);
                if (existing == null || "服务中".equals(status)) {
                    statusMap.put(customerPhone, status);
                }
            });
        }
        return statusMap;
    }


    @Override
    public Set<String> listServingCustomerPhones(String agentAccountRowId) {
        List<Map<String, Object>> filters = List.of(
                filter(ASSIGN_AGENT_CONTROL_ID, agentAccountRowId,29,1,24),
                filter(ASSIGN_SERVICE_STATUS_CONTROL_ID, "服务中",11,1,2)
        );
        JsonNode root = getFilterRows(ASSIGNMENT_WORKSHEET_ID, filters, 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return Set.of();
        }

        Set<String> customers = new HashSet<>();
        JsonNode rows = root.path("data").path("rows");
        if (rows.isArray()) {
            rows.forEach(row -> {
                String customerPhone = extractAsText(row, ASSIGN_CUSTOMER_PHONE_CONTROL_ID);
                if (!customerPhone.isBlank()) {
                    customers.add(customerPhone);
                }
            });
        }
        return customers;
    }

    @Override
    public Set<String> listOnlineAgents() {
        List<Map<String, Object>> filters = List.of(filter(AGENT_LOGIN_STATUS_CONTROL_ID, "在线",11,1,2));
        JsonNode root = getFilterRows(AGENT_LOGIN_WORKSHEET_ID, filters, 500);
        if (root == null || !root.path("success").asBoolean(false)) {
            return Set.of();
        }

        Set<String> online = new HashSet<>();
        JsonNode rows = root.path("data").path("rows");
        if (rows.isArray()) {
            rows.forEach(row -> {
                String agentRowId = extractAsText(row, AGENT_LOGIN_ACCOUNT_CONTROL_ID);
                if (!agentRowId.isBlank()) {
                    online.add(agentRowId);
                }
            });
        }
        return online;
    }

    private JsonNode getFilterRows(String worksheetId, List<Map<String, Object>> filters, int pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("pageSize", pageSize);
        body.put("pageIndex", 1);
        body.put("listType", 0);
        body.put("controls", List.of());
        body.put("filters", filters);
        return post("/api/v2/open/worksheet/getFilterRows", body);
    }

    private JsonNode addRow(String worksheetId, List<Map<String, Object>> controls) {
        List<Map<String, Object>> allControls = new ArrayList<>(controls);
        if (crmConfig.getOwnerId() != null && !crmConfig.getOwnerId().isBlank()) {
            allControls.add(control("ownerid", crmConfig.getOwnerId()));
        }

        Map<String, Object> body = new HashMap<>();
        body.put("appKey", crmConfig.getAppKey());
        body.put("sign", crmConfig.getSign());
        body.put("worksheetId", worksheetId);
        body.put("triggerWorkflow", true);
        body.put("controls", allControls);

        return post("/api/v2/open/worksheet/addRow", body);
    }

    private JsonNode firstRow(JsonNode root) {
        JsonNode rows = root.path("data").path("rows");
        return rows.isArray() && rows.size() > 0 ? rows.get(0) : null;
    }

    private JsonNode post(String path, Object body) {
        try {
            String bodyText = abbreviateSafe(body);
            log.info("CRM post request. path={}, body={}", path, bodyText);
            String resp = webClient.post()
                    .uri(crmConfig.getBaseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (resp == null || resp.isBlank()) {
                log.warn("CRM post empty response. path={}", path);
                return null;
            }
            log.info("CRM post response. path={}, body={}", path, abbreviate(resp));
            return objectMapper.readTree(resp);
        } catch (Exception e) {
            log.error("CRM post failed path={}", path, e);
            return null;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(CRM_TIME_FORMAT);
    }

    private static Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
    }

    private static Map<String, Object> selectControl(String controlId, String value) {
        Map<String, Object> item = control(controlId, value);
        item.put("valueType", 2);
        return item;
    }

    private static Map<String, Object> filter(String controlId, String value, int dataType ,int spliceType,int filterType) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("dataType", dataType);
        item.put("spliceType", spliceType);
        item.put("filterType", filterType);
        item.put("value", value);
        return item;
    }

    private String extractAsText(JsonNode row, String fieldName) {
        JsonNode node = row.get(fieldName);
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            String text = node.asText();
            String relationId = normalizeRelationRowId(text);
            return relationId == null || relationId.isBlank() ? text : relationId;
        }
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.has("sid")) {
                return first.path("sid").asText("");
            }
            if (first.has("id")) {
                return first.path("id").asText("");
            }
            if (first.has("name")) {
                return first.path("name").asText("");
            }
            return first.toString();
        }
        return node.toString();
    }

    /**
     * 兼容 CRM 关联字段多种返回格式：
     * 1) 纯 rowId 字符串
     * 2) JSON 数组字符串（如 [{"sid":"..."}]）
     * 3) JSON 对象字符串（如 {"rowid":"..."}）
     */
    private String normalizeRelationRowId(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String text = raw.trim();
        if (!(text.startsWith("{") || text.startsWith("["))) {
            return text;
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node.isArray() && !node.isEmpty()) {
                JsonNode first = node.get(0);
                if (first.has("sid")) {
                    return first.path("sid").asText("");
                }
                if (first.has("id")) {
                    return first.path("id").asText("");
                }
                if (first.has("rowid")) {
                    return first.path("rowid").asText("");
                }
            }
            if (node.isObject()) {
                if (node.has("sid")) {
                    return node.path("sid").asText("");
                }
                if (node.has("id")) {
                    return node.path("id").asText("");
                }
                if (node.has("rowid")) {
                    return node.path("rowid").asText("");
                }
            }
        } catch (Exception ignore) {
            return text;
        }
        return text;
    }

    private String normalizeSender(String sender) {
        return switch (sender) {
            case "客户" -> "customer";
            case "AI" -> "ai";
            case "人工" -> "agent";
            default -> "system";
        };
    }

    private Instant parseCrmTime(String text) {
        if (text == null || text.isBlank()) {
            return Instant.now();
        }
        try {
            LocalDateTime dt = LocalDateTime.parse(text, CRM_TIME_FORMAT);
            return dt.atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return Instant.now();
        }
    }

    private String abbreviateSafe(Object body) {
        try {
            return abbreviate(objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return "<serialize-failed>";
        }
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ");
        return normalized.length() > 1200 ? normalized.substring(0, 1200) + "...(truncated)" : normalized;
    }
}
