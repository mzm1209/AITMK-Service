package com.example.aitmk.controller;

import com.example.aitmk.model.domain.SessionTransferRequest;
import com.example.aitmk.model.domain.SessionTransferUpsertRequest;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/session-transfers")
@RequiredArgsConstructor
public class SessionTransferController {

    private static final String WORKSHEET_ID = "69fd8e82cd23604cb45f0ccd";
    private static final String CUSTOMER_PHONE_CONTROL_ID = "69fd8e82cd23604cb45f0cce";
    private static final String FROM_AGENT_CONTROL_ID = "69fd8fb4cd23604cb45f0eb6";
    private static final String TO_AGENT_CONTROL_ID = "69fd8fb4cd23604cb45f0eb8";
    private static final String TRANSFER_TIME_CONTROL_ID = "69fd8fb4cd23604cb45f0eba";

    private static final DateTimeFormatter CRM_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");

    private final CrmOpenApiService crmOpenApiService;
    private final AgentDispatchService agentDispatchService;

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody SessionTransferUpsertRequest request) {
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "新增会话转移记录失败"));
        return ResponseEntity.ok(Map.of("success", true, "rowId", root.path("data").asText("")));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> edit(@PathVariable String rowId, @Valid @RequestBody SessionTransferUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId不能为空"));
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, buildControls(request), true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "修改会话转移记录失败"));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> delete(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId不能为空"));
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "删除会话转移记录失败"));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "50") int pageSize,
                                  @RequestParam(defaultValue = "1") int pageIndex,
                                  @RequestParam(required = false) String customerPhone) {
        List<Map<String, Object>> filters = new ArrayList<>();
        if (StringUtils.hasText(customerPhone)) {
            filters.add(filter(CUSTOMER_PHONE_CONTROL_ID, customerPhone.trim(), 2, 1, 2));
        }
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, filters, pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "查询会话转移记录失败"));
        return ResponseEntity.ok(Map.of("success", true, "total", root.path("data").path("total").asInt(0), "rows", root.path("data").path("rows")));
    }

    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(@Valid @RequestBody SessionTransferRequest request) {
        String customerPhone = request.getCustomerPhone().trim();
        String target = request.getTargetAgentRowId().trim();
        String current = agentDispatchService.getAssignedAgent(customerPhone).orElse(null);
        if (!StringUtils.hasText(current)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "客户当前无已分配坐席"));
        }
        if (current.equals(target)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "目标坐席与当前坐席相同"));
        }

        try {
            crmOpenApiService.closeServingAssignment(customerPhone);
            crmOpenApiService.addAssignmentRecord(customerPhone, target, "服务中");
            crmOpenApiService.assignAiReception(customerPhone);

            SessionTransferUpsertRequest upsert = new SessionTransferUpsertRequest();
            upsert.setCustomerPhone(customerPhone);
            upsert.setFromAgentRowId(current);
            upsert.setToAgentRowId(target);
            upsert.setTransferTime(LocalDateTime.now().format(CRM_TIME_FORMAT));
            crmOpenApiService.frontendAddRow(WORKSHEET_ID, buildControls(upsert), true);

            Map<String, String> assignments = agentDispatchService.assignmentsSnapshot();
            assignments.put(customerPhone, target);
            agentDispatchService.replaceState(agentDispatchService.onlineAgentsSnapshot(), assignments);

            return ResponseEntity.ok(Map.of("success", true, "fromAgent", current, "toAgent", target));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "转移会话失败", "error", ex.getMessage()));
        }
    }

    private List<Map<String, Object>> buildControls(SessionTransferUpsertRequest request) {
        return List.of(
                control(CUSTOMER_PHONE_CONTROL_ID, request.getCustomerPhone()),
                control(FROM_AGENT_CONTROL_ID, request.getFromAgentRowId()),
                control(TO_AGENT_CONTROL_ID, request.getToAgentRowId()),
                control(TRANSFER_TIME_CONTROL_ID, StringUtils.hasText(request.getTransferTime()) ? request.getTransferTime() : LocalDateTime.now().format(CRM_TIME_FORMAT))
        );
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
