package com.example.aitmk.service.impl;

import com.example.aitmk.config.CrmConfig;
import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CrmOpenApiServiceImpl implements CrmOpenApiService {

    private static final String LOGIN_WORKSHEET_ID = "imzhgl";
    private static final String LOGIN_ACCOUNT_CONTROL_ID = "69abab83433ec9f4b5e6ce0e";
    private static final String LOGIN_PASSWORD_CONTROL_ID = "69abacc3433ec9f4b5e6ce25";
    private static final String LOGIN_RELATED_USER_CONTROL_ID = "69abacc3433ec9f4b5e6ce26";

    private static final String AGENT_LOGIN_WORKSHEET_ID = "zxzt";
    private static final String AGENT_LOGIN_ACCOUNT_CONTROL_ID = "69abbb3e433ec9f4b5e6d083";
    private static final String AGENT_LOGIN_STATUS_CONTROL_ID = "69abbb3e433ec9f4b5e6d085";
    private static final String AGENT_LOGIN_TIME_CONTROL_ID = "69abbb3e433ec9f4b5e6d086";

    private static final String ASSIGNMENT_WORKSHEET_ID = "ltjl";
    private static final String ASSIGN_CUSTOMER_PHONE_CONTROL_ID = "69abb3a0433ec9f4b5e6cff4";
    private static final String ASSIGN_AGENT_CONTROL_ID = "69abbcaf433ec9f4b5e6d0f6";
    private static final String ASSIGN_TIME_CONTROL_ID = "69abb8d7433ec9f4b5e6d05f";
    private static final String ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID = "69abb984433ec9f4b5e6d069";
    private static final String ASSIGN_SERVICE_STATUS_CONTROL_ID = "69abba17433ec9f4b5e6d06e";

    private static final String CHAT_WORKSHEET_ID = "ltjl1";
    private static final String CHAT_BUSINESS_ACCOUNT_CONTROL_ID = "69abbccf433ec9f4b5e6d0fe";
    private static final String CHAT_CUSTOMER_PHONE_CONTROL_ID = "69abbd3b433ec9f4b5e6d108";
    private static final String CHAT_AGENT_CONTROL_ID = "69abbd3b433ec9f4b5e6d109";
    private static final String CHAT_SENDER_CONTROL_ID = "69abbfff433ec9f4b5e6d226";
    private static final String CHAT_SEND_TIME_CONTROL_ID = "69abbfff433ec9f4b5e6d227";
    private static final String CHAT_CONTENT_CONTROL_ID = "69abbfff433ec9f4b5e6d228";

    private static final DateTimeFormatter CRM_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");

    private final CrmConfig crmConfig;
    private final ObjectMapper objectMapper;

    private final WebClient webClient = WebClient.builder().build();

    @Override
    public Optional<CrmAgentAccount> verifyLogin(String username, String password) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("appKey", crmConfig.getAppKey());
            body.put("sign", crmConfig.getSign());
            body.put("worksheetId", LOGIN_WORKSHEET_ID);
            body.put("pageSize", 50);
            body.put("pageIndex", 1);
            body.put("listType", 0);
            body.put("controls", List.of());

            List<Map<String, Object>> filters = new ArrayList<>();
            filters.add(filter(LOGIN_ACCOUNT_CONTROL_ID, username));
            filters.add(filter(LOGIN_PASSWORD_CONTROL_ID, password));
            body.put("filters", filters);

            JsonNode root = post("/api/v2/open/worksheet/getFilterRows", body);
            if (root == null || !root.path("success").asBoolean(false)) {
                return Optional.empty();
            }

            int total = root.path("data").path("total").asInt(0);
            if (total <= 0) {
                return Optional.empty();
            }

            JsonNode first = root.path("data").path("rows").isArray() && root.path("data").path("rows").size() > 0
                    ? root.path("data").path("rows").get(0)
                    : null;
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
    public boolean addAgentLoginRecord(String agentAccountRowId, String status) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(AGENT_LOGIN_ACCOUNT_CONTROL_ID, agentAccountRowId));
        controls.add(selectControl(AGENT_LOGIN_STATUS_CONTROL_ID, status));
        controls.add(control(AGENT_LOGIN_TIME_CONTROL_ID, now()));
        return addRow(AGENT_LOGIN_WORKSHEET_ID, controls);
    }

    @Override
    public boolean addAssignmentRecord(String customerPhone, String agentAccountRowId, String serviceStatus) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(ASSIGN_CUSTOMER_PHONE_CONTROL_ID, customerPhone));
        controls.add(control(ASSIGN_AGENT_CONTROL_ID, agentAccountRowId));
        controls.add(control(ASSIGN_TIME_CONTROL_ID, now()));
        controls.add(control(ASSIGN_CUSTOMER_LAST_CALL_TIME_CONTROL_ID, now()));
        controls.add(selectControl(ASSIGN_SERVICE_STATUS_CONTROL_ID, serviceStatus));
        return addRow(ASSIGNMENT_WORKSHEET_ID, controls);
    }

    @Override
    public boolean addChatRecord(String businessAccountId,
                                 String customerPhone,
                                 String agentAccountRowId,
                                 String sender,
                                 String message) {
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(control(CHAT_BUSINESS_ACCOUNT_CONTROL_ID, businessAccountId));
        controls.add(control(CHAT_CUSTOMER_PHONE_CONTROL_ID, customerPhone));
        if (agentAccountRowId != null && !agentAccountRowId.isBlank()) {
            controls.add(control(CHAT_AGENT_CONTROL_ID, agentAccountRowId));
        }
        controls.add(selectControl(CHAT_SENDER_CONTROL_ID, sender));
        controls.add(control(CHAT_SEND_TIME_CONTROL_ID, now()));
        controls.add(control(CHAT_CONTENT_CONTROL_ID, message));
        return addRow(CHAT_WORKSHEET_ID, controls);
    }

    private boolean addRow(String worksheetId, List<Map<String, Object>> controls) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("appKey", crmConfig.getAppKey());
            body.put("sign", crmConfig.getSign());
            body.put("worksheetId", worksheetId);
            body.put("triggerWorkflow", true);
            body.put("controls", controls);
            if (crmConfig.getOwnerId() != null && !crmConfig.getOwnerId().isBlank()) {
                controls.add(control("ownerid", crmConfig.getOwnerId()));
            }

            JsonNode root = post("/api/v2/open/worksheet/addRow", body);
            return root != null && root.path("success").asBoolean(false);
        } catch (Exception e) {
            log.error("CRM addRow failed worksheetId={}", worksheetId, e);
            return false;
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            String resp = webClient.post()
                    .uri(crmConfig.getBaseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (resp == null || resp.isBlank()) {
                return null;
            }
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

    private static Map<String, Object> filter(String controlId, String value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("dataType", 6);
        item.put("spliceType", 1);
        item.put("filterType", 13);
        item.put("value", value);
        return item;
    }

    private String extractAsText(JsonNode row, String fieldName) {
        JsonNode node = row.get(fieldName);
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }
}
