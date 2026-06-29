package com.example.aitmk.service;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.domain.LeadRecord;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.service.v2.ResourceQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FollowUpService {

    private static final String FOLLOW_UP_WORKSHEET_ID = "follow_up";
    private static final String LEAD_WORKSHEET_ID = "leads_bank";

    private static final String CONTROL_TYPE = "66b359657e23d13674eeb6d7";
    private static final String CONTROL_SUMMARY = "66af0e343e774217ade21117";
    private static final String CONTROL_DETAILS = "66b5d6663e774217ade9e8a6";
    private static final String CONTROL_REMINDER_AT = "66af1a7c507253a77279bca6";
    private static final String CONTROL_STAFF = "66af1a7c507253a77279bcad";
    private static final String CONTROL_LEAD = "66b4ef231579a408e580372d";
    private static final String CONTROL_CENTER = "67b6f3bd286831392e291c3e";
    private static final String CONTROL_CREATED_AT = "66bd88553e774217adf08fe9";

    private static final String CLUE_CENTER = "66eeb5b0f53d52846e007a35";

    private final CrmOpenApiService crmOpenApiService;
    private final ResourceQueryService resourceQueryService;
    private final ObjectMapper objectMapper;

    public FollowUpListView list(String leadRowId, Integer size, AuthenticatedUser user) {
        validateLeadRowId(leadRowId);
        int pageSize = Math.max(1, Math.min(size == null ? 20 : size, 100));
        JsonNode root = crmOpenApiService.frontendGetFilterRows(
                FOLLOW_UP_WORKSHEET_ID,
                List.of(filter(CONTROL_LEAD, leadRowId.trim(), 29, 1, 24)),
                pageSize,
                1,
                0,
                List.of()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "FOLLOW_UP_QUERY_FAILED", "查询跟进记录失败", root);
        }
        JsonNode rows = root.path("data").path("rows");
        List<FollowUpView> items = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                items.add(toView(row));
            }
        }
        return new FollowUpListView(items, root.path("data").path("total").asInt(items.size()));
    }

    public FollowUpView create(CreateFollowUpRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        validateLeadRowId(request.leadRowId());
        if (!StringUtils.hasText(request.type())) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "FOLLOW_UP_TYPE_MISSING", "跟进类型不能为空");
        }
        if (!StringUtils.hasText(request.summary())) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "FOLLOW_UP_SUMMARY_MISSING", "跟进摘要不能为空");
        }
        if (request.resourceId() != null) {
            ResourceEntity resource = resourceQueryService.get(request.resourceId(), user);
            if (StringUtils.hasText(resource.getCustomerPhone())) {
                // Access validation is the important part; lead linkage remains CRM-owned.
            }
        }

        Object center = request.center();
        if (center == null || (center instanceof String str && !StringUtils.hasText(str))) {
            center = defaultCenterFromLead(request.leadRowId());
        }

        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(selectControl(CONTROL_TYPE, request.type().trim()));
        controls.add(control(CONTROL_SUMMARY, request.summary().trim()));
        if (StringUtils.hasText(request.details())) {
            controls.add(control(CONTROL_DETAILS, request.details().trim()));
        }
        if (StringUtils.hasText(request.reminderAt())) {
            controls.add(control(CONTROL_REMINDER_AT, request.reminderAt().trim()));
        }
        controls.add(control(CONTROL_STAFF, staffAccountId(user)));
        controls.add(control(CONTROL_LEAD, request.leadRowId().trim()));
        Object centerValue = relationValue(center);
        if (centerValue != null) {
            controls.add(control(CONTROL_CENTER, centerValue));
        }

        JsonNode root = crmOpenApiService.frontendAddRow(
                FOLLOW_UP_WORKSHEET_ID,
                controls,
                request.triggerWorkflow() == null || request.triggerWorkflow()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "FOLLOW_UP_CREATE_FAILED", "创建跟进记录失败", root);
        }
        String rowId = root.path("data").asText("");
        JsonNode created = StringUtils.hasText(rowId)
                ? crmOpenApiService.getRowByRowId(FOLLOW_UP_WORKSHEET_ID, rowId)
                : null;
        if (created != null) {
            return toView(created);
        }
        return new FollowUpView(rowId, request.type(), request.summary(), request.details(),
                request.reminderAt(), null, null, displayText(center), Map.of());
    }

    public void validateResourceAccess(Long resourceId, AuthenticatedUser user) {
        if (resourceId != null) {
            resourceQueryService.get(resourceId, user);
        }
    }

    private Object defaultCenterFromLead(String leadRowId) {
        JsonNode lead = crmOpenApiService.getRowByRowId(LEAD_WORKSHEET_ID, leadRowId);
        if (lead == null) {
            return null;
        }
        JsonNode center = lead.get(CLUE_CENTER);
        if (center == null || center.isNull()) {
            return null;
        }
        return objectMapper.convertValue(center, Object.class);
    }

    private FollowUpView toView(JsonNode row) {
        return new FollowUpView(
                row.path("rowid").asText(""),
                displayText(row.get(CONTROL_TYPE)),
                displayText(row.get(CONTROL_SUMMARY)),
                displayText(row.get(CONTROL_DETAILS)),
                displayText(row.get(CONTROL_REMINDER_AT)),
                firstText(row.get(CONTROL_CREATED_AT), row.get("ctime"), row.get("createdAt"), row.get("createTime")),
                displayText(row.get(CONTROL_STAFF)),
                displayText(row.get(CONTROL_CENTER)),
                objectMapper.convertValue(row, new TypeReference<Map<String, Object>>() {})
        );
    }

    private String staffAccountId(AuthenticatedUser user) {
        return LeadRecord.extractAccountId(user.getRelatedUserIds())
                .filter(StringUtils::hasText)
                .orElse(user.getAccountRowId());
    }

    private void validateLeadRowId(String leadRowId) {
        if (!StringUtils.hasText(leadRowId)) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "LEAD_ROW_ID_MISSING", "leadRowId 不能为空");
        }
    }

    private Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new LinkedHashMap<>();
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
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("controlId", controlId);
        item.put("dataType", dataType);
        item.put("spliceType", spliceType);
        item.put("filterType", filterType);
        item.put("value", value);
        return item;
    }

    private Object relationValue(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(value);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                JsonNode parsed = objectMapper.readTree(text);
                Object parsedValue = relationValue(parsed);
                return parsedValue == null ? text : parsedValue;
            } catch (Exception ignored) {
                return text;
            }
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                return null;
            }
            return relationValue(node.get(0));
        }
        if (node.isObject()) {
            for (String field : List.of("departmentId", "accountId", "rowid", "rowId", "id", "sid", "value")) {
                JsonNode child = node.get(field);
                if (child != null && !child.isNull()) {
                    Object candidate = relationValue(child);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String text = displayText(node);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String displayText(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode node = value instanceof JsonNode jsonNode ? jsonNode : objectMapper.valueToTree(value);
        return displayText(node);
    }

    private String displayText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode item : node) {
                String text = displayText(item);
                if (StringUtils.hasText(text)) {
                    parts.add(text);
                }
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
        for (String field : List.of("name", "fullname", "fullName", "accountName", "departmentName", "value", "text", "title", "rowid")) {
            JsonNode child = node.get(field);
            if (child != null && !child.isNull()) {
                String text = displayText(child);
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return node.toString();
    }
}
