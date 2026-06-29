package com.example.aitmk.service;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.domain.LeadRecord;
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
public class AppointmentService {

    private static final String APPOINTMENT_WORKSHEET_ID = "66e473021d111072e718410e";
    private static final String LEAD_WORKSHEET_ID = "leads_bank";

    private static final String CONTROL_CONTACT_NUMBER = "66b30ef13e774217ade66e78";
    private static final String CONTROL_STAFF_FOLLOW_UP = "66b85145504017592ec5a818";
    private static final String CONTROL_LEAD_SOURCE = "66b3692d3e774217ade72e26";
    private static final String CONTROL_STUDENT_NAME = "66b1f86d9d2c721e325fac78";
    private static final String CONTROL_FOLLOW_UP = "66b4ef231579a408e580372c";
    private static final String CONTROL_GRADE = "66b30ef13e774217ade66e77";
    private static final String CONTROL_SCHOOL = "66b3692d3e774217ade72e25";
    private static final String CONTROL_PARENT_NAME = "66bed8be666ad6264b6cb1b4";
    private static final String CONTROL_LEAD_RECORD = "66b84d2c827616d991cb42a3";
    private static final String CONTROL_PROGRAM_INTEREST = "66b310829b545d2337ac4433";
    private static final String CONTROL_APPOINTMENT_ID = "66bf00f8b71c4a09d34876ca";
    private static final String CONTROL_CENTER_RELATION = "66b85145504017592ec5a815";
    private static final String CONTROL_ASSIGNED_TIME = "66bb400b921cf135f27cd905";
    private static final String CONTROL_APPOINTMENT_DATE = "66b85145504017592ec5a814";
    private static final String CONTROL_APPOINTMENT_STATUS = "66b5e34a7e23d13674f24129";
    private static final String CONTROL_APPOINTMENT_INFO = "66b85a6fb71c4a09d340f780";
    private static final String CONTROL_FOLLOW_UP_DUE_AT = "66bb400b921cf135f27cd903";
    private static final String CONTROL_FOLLOW_UP_STATUS = "66b36b8cce042770da7218b0";
    private static final String CONTROL_INTEREST_LEVEL = "66c5d816666ad6264b75de3f";
    private static final String CONTROL_CENTER_DEPARTMENT = "66eeb78df53d52846e007a3d";
    private static final String CONTROL_VISIT_STATUS = "677ced5ef4234762a6a6db4f";
    private static final String CONTROL_LEADS_CHANNEL = "67d7aa0a286831392e2932cc";
    private static final String CONTROL_INTERN = "67d7ab3d286831392e2932d6";

    private static final String CLUE_PHONE = "687fa4dd005dfd294df9dc3e";
    private static final String CLUE_STUDENT_NAME = "66b1f86d9d2c721e325fac78";
    private static final String CLUE_GRADE = "66b30ef13e774217ade66e77";
    private static final String CLUE_SCHOOL = "66b3692d3e774217ade72e25";
    private static final String CLUE_PARENT_NAME = "66bdb9a46e5c3bc8e0c7df9a";
    private static final String CLUE_PROGRAM_INTEREST = "66b310829b545d2337ac4433";
    private static final String CLUE_CENTER = "66eeb5b0f53d52846e007a35";

    private final CrmOpenApiService crmOpenApiService;
    private final ResourceQueryService resourceQueryService;
    private final ObjectMapper objectMapper;

    public AppointmentListView list(String leadRowId,
                                    String followUpRowId,
                                    Long resourceId,
                                    Integer size,
                                    AuthenticatedUser user) {
        validateResourceAccess(resourceId, user);
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(leadRowId)) {
            filters.add(filter(CONTROL_LEAD_RECORD, leadRowId.trim(), 29, 1, 24));
        }
        if (StringUtils.hasText(followUpRowId)) {
            filters.add(filter(CONTROL_FOLLOW_UP, followUpRowId.trim(), 29, 1, 24));
        }
        if (filters.isEmpty()) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "APPOINTMENT_FILTER_MISSING",
                    "leadRowId 或 followUpRowId 至少提供一个");
        }

        int pageSize = Math.max(1, Math.min(size == null ? 20 : size, 100));
        JsonNode root = crmOpenApiService.frontendGetFilterRows(
                APPOINTMENT_WORKSHEET_ID, filters, pageSize, 1, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "APPOINTMENT_QUERY_FAILED", "查询预约记录失败", root);
        }

        JsonNode rows = root.path("data").path("rows");
        List<AppointmentView> items = new ArrayList<>();
        if (rows.isArray()) {
            for (JsonNode row : rows) {
                items.add(toView(row));
            }
        }
        return new AppointmentListView(items, root.path("data").path("total").asInt(items.size()));
    }

    public AppointmentView create(CreateAppointmentRequest request, AuthenticatedUser user) {
        if (request == null) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求不能为空");
        }
        validateResourceAccess(request.resourceId(), user);
        if (!StringUtils.hasText(request.leadRowId()) && !StringUtils.hasText(request.followUpRowId())) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "APPOINTMENT_RELATION_MISSING",
                    "leadRowId 或 followUpRowId 至少提供一个");
        }
        if (!StringUtils.hasText(request.appointmentDate())) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "APPOINTMENT_DATE_MISSING", "预约时间不能为空");
        }

        JsonNode lead = StringUtils.hasText(request.leadRowId())
                ? crmOpenApiService.getRowByRowId(LEAD_WORKSHEET_ID, request.leadRowId().trim())
                : null;
        Object center = firstNonBlank(request.center(), valueFromLead(lead, CLUE_CENTER));

        List<Map<String, Object>> controls = new ArrayList<>();
        addText(controls, CONTROL_CONTACT_NUMBER, firstNonBlank(request.contactNumber(), valueFromLead(lead, CLUE_PHONE)));
        addText(controls, CONTROL_STUDENT_NAME, firstNonBlank(request.studentName(), valueFromLead(lead, CLUE_STUDENT_NAME)));
        addSelect(controls, CONTROL_GRADE, firstNonBlank(request.grade(), valueFromLead(lead, CLUE_GRADE)));
        addText(controls, CONTROL_SCHOOL, firstNonBlank(request.school(), valueFromLead(lead, CLUE_SCHOOL)));
        addText(controls, CONTROL_PARENT_NAME, firstNonBlank(request.parentName(), valueFromLead(lead, CLUE_PARENT_NAME)));
        addSelect(controls, CONTROL_PROGRAM_INTEREST, firstNonBlank(request.programInterest(), valueFromLead(lead, CLUE_PROGRAM_INTEREST)));
        addText(controls, CONTROL_APPOINTMENT_DATE, request.appointmentDate().trim());
        addText(controls, CONTROL_APPOINTMENT_INFO, request.appointmentInfo());
        addSelect(controls, CONTROL_APPOINTMENT_STATUS,
                StringUtils.hasText(request.appointmentStatus()) ? request.appointmentStatus().trim() : "Appointed, Waiting for visit ");
        addSelect(controls, CONTROL_FOLLOW_UP_STATUS, request.followUpStatus());
        addText(controls, CONTROL_FOLLOW_UP_DUE_AT, request.followUpDueAt());
        addText(controls, CONTROL_ASSIGNED_TIME, request.assignedTime());
        addSelect(controls, CONTROL_VISIT_STATUS, request.visitStatus());
        if (request.interestLevel() != null) {
            controls.add(control(CONTROL_INTEREST_LEVEL, request.interestLevel()));
        }
        addSelect(controls, CONTROL_LEADS_CHANNEL, request.leadsChannel());
        addSelect(controls, CONTROL_INTERN, request.intern());
        addRelation(controls, CONTROL_LEAD_RECORD, request.leadRowId());
        addRelation(controls, CONTROL_LEAD_SOURCE, request.leadRowId());
        addRelation(controls, CONTROL_FOLLOW_UP, request.followUpRowId());
        addText(controls, CONTROL_STAFF_FOLLOW_UP, staffAccountId(user));

        Object centerValue = relationValue(center);
        if (centerValue != null) {
            controls.add(control(CONTROL_CENTER_RELATION, centerValue));
            controls.add(control(CONTROL_CENTER_DEPARTMENT, centerValue));
        }

        JsonNode root = crmOpenApiService.frontendAddRow(
                APPOINTMENT_WORKSHEET_ID,
                controls,
                request.triggerWorkflow() == null || request.triggerWorkflow()
        );
        if (root == null || !root.path("success").asBoolean(false)) {
            throw new V2Exception(HttpStatus.BAD_GATEWAY, "APPOINTMENT_CREATE_FAILED", "创建预约记录失败", root);
        }

        String rowId = root.path("data").asText("");
        JsonNode created = StringUtils.hasText(rowId)
                ? crmOpenApiService.getRowByRowId(APPOINTMENT_WORKSHEET_ID, rowId)
                : null;
        return created == null ? fallbackView(rowId, request, center) : toView(created);
    }

    private void validateResourceAccess(Long resourceId, AuthenticatedUser user) {
        if (resourceId != null) {
            resourceQueryService.get(resourceId, user);
        }
    }

    private AppointmentView toView(JsonNode row) {
        return new AppointmentView(
                row.path("rowid").asText(""),
                displayText(row.get(CONTROL_APPOINTMENT_ID)),
                displayText(row.get(CONTROL_CONTACT_NUMBER)),
                displayText(row.get(CONTROL_STUDENT_NAME)),
                displayText(row.get(CONTROL_PARENT_NAME)),
                displayText(row.get(CONTROL_APPOINTMENT_DATE)),
                displayText(row.get(CONTROL_APPOINTMENT_INFO)),
                displayText(row.get(CONTROL_APPOINTMENT_STATUS)),
                displayText(row.get(CONTROL_VISIT_STATUS)),
                displayText(row.get(CONTROL_FOLLOW_UP_STATUS)),
                displayText(row.get(CONTROL_FOLLOW_UP_DUE_AT)),
                firstText(row.get(CONTROL_CENTER_RELATION), row.get(CONTROL_CENTER_DEPARTMENT)),
                displayText(row.get(CONTROL_STAFF_FOLLOW_UP)),
                relationValueAsString(row.get(CONTROL_LEAD_RECORD)),
                relationValueAsString(row.get(CONTROL_FOLLOW_UP)),
                objectMapper.convertValue(row, new TypeReference<Map<String, Object>>() {})
        );
    }

    private AppointmentView fallbackView(String rowId, CreateAppointmentRequest request, Object center) {
        return new AppointmentView(rowId, null, request.contactNumber(), request.studentName(), request.parentName(),
                request.appointmentDate(), request.appointmentInfo(), request.appointmentStatus(), request.visitStatus(),
                request.followUpStatus(), request.followUpDueAt(), displayText(center), null,
                request.leadRowId(), request.followUpRowId(), Map.of());
    }

    private void addText(List<Map<String, Object>> controls, String controlId, Object value) {
        Object normalized = textValue(value);
        if (normalized != null) {
            controls.add(control(controlId, normalized));
        }
    }

    private void addSelect(List<Map<String, Object>> controls, String controlId, Object value) {
        Object normalized = textValue(value);
        if (normalized != null) {
            controls.add(selectControl(controlId, normalized.toString()));
        }
    }

    private void addRelation(List<Map<String, Object>> controls, String controlId, String rowId) {
        if (StringUtils.hasText(rowId)) {
            controls.add(control(controlId, rowId.trim()));
        }
    }

    private Object firstNonBlank(Object first, Object second) {
        Object normalizedFirst = textOrRawValue(first);
        if (normalizedFirst instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        if (normalizedFirst != null && !(normalizedFirst instanceof String)) {
            return normalizedFirst;
        }
        return textOrRawValue(second);
    }

    private Object valueFromLead(JsonNode lead, String controlId) {
        return lead == null ? null : lead.get(controlId);
    }

    private Object textValue(Object value) {
        Object normalized = textOrRawValue(value);
        if (normalized instanceof String text) {
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        return normalized;
    }

    private Object textOrRawValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        String display = displayText(value);
        return StringUtils.hasText(display) ? display : value;
    }

    private String staffAccountId(AuthenticatedUser user) {
        return LeadRecord.extractAccountId(user.getRelatedUserIds())
                .filter(StringUtils::hasText)
                .orElse(user.getAccountRowId());
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
                return parsedValue == null ? text.trim() : parsedValue;
            } catch (Exception ignored) {
                return text.trim();
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

    private String relationValueAsString(JsonNode node) {
        Object value = relationValue(node);
        return value == null ? null : value.toString();
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
