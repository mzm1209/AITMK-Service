package com.example.aitmk.controller.v2;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.domain.LeadRecord;
import com.example.aitmk.model.entity.LeadRecordEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.LeadRecordRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.CurrentUser;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.WorksheetFieldService;
import com.example.aitmk.service.impl.ClueIntegrationService;
import com.example.aitmk.service.v2.ResourceQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v2/resources")
@RequiredArgsConstructor
public class ResourceV2Controller {

    private final ResourceQueryService service;
    private final ClueIntegrationService clueIntegrationService;
    private final WorksheetFieldService worksheetFieldService;
    private final CrmOpenApiService crmOpenApiService;
    private final LeadRecordRepository leadRecordRepository;
    private final ResourceRepository resourceRepository;
    private final ObjectMapper objectMapper;

    private static final String CLUE_WORKSHEET_ID = "leads_bank";

    @GetMapping("/{id}")
    public Response<ResourceView> get(@PathVariable Long id) {
        return Response.ok(service.view(id, CurrentUser.get()));
    }

    @GetMapping("/{id}/conversations")
    public Response<CursorPage<ConversationHistoryView>> conversations(
            @PathVariable Long id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size) {
        return Response.ok(service.conversations(id, cursor, size, CurrentUser.get()));
    }

    @GetMapping("/{id}/assignments")
    public Response<CursorPage<AssignmentView>> assignments(
            @PathVariable Long id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "30") int size) {
        return Response.ok(service.assignments(id, cursor, size, CurrentUser.get()));
    }

    /**
     * CRM profile for the customer info panel.
     * Returns linked status, clue data, and field config for rendering.
     */
    @GetMapping("/{id}/crm-profile")
    public Response<CrmProfileView> crm(@PathVariable Long id) {
        AuthenticatedUser user = CurrentUser.get();
        ResourceEntity resource = service.get(id, user);
        String phone = resource.getCustomerPhone();
        String resourceName = resource.getCustomerName() == null ? "" : resource.getCustomerName();

        boolean linked = false;
        String rowId = null;
        Object clue = null;

        if (StringUtils.hasText(phone)) {
            Optional<LeadRecord> leadOpt = clueIntegrationService.lookupLeadByPhone(phone.trim());
            if (leadOpt.isPresent()) {
                LeadRecord lead = leadOpt.get();
                linked = lead.getRowId() != null;
                rowId = lead.getRowId();
                clue = lead;
            }
        }

        Map<String, Map<String, Object>> fieldsConfig;
        try {
            fieldsConfig = worksheetFieldService.getFieldsConfig(CLUE_WORKSHEET_ID);
        } catch (Exception ex) {
            log.warn("Failed to load fields config for crm-profile. resourceId={}", id, ex);
            fieldsConfig = Map.of();
        }

        return Response.ok(new CrmProfileView(
                id.toString(), phone == null ? "" : phone, resourceName,
                linked, rowId, clue, fieldsConfig));
    }

    /**
     * Link a CRM clue to this resource.
     * Validates the rowId exists in CRM, then upserts lead_records.
     */
    @PostMapping("/{id}/link-lead")
    public Response<Map<String, Object>> linkLead(@PathVariable Long id,
                                                   @RequestBody LinkLeadRequest request) {
        AuthenticatedUser user = CurrentUser.get();
        ResourceEntity resource = service.get(id, user);
        String rowId = request.rowId();

        // 1. Query CRM for the specific rowId to validate it exists and get phone
        JsonNode row = crmOpenApiService.getRowByRowId(CLUE_WORKSHEET_ID, rowId);
        if (row == null) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "CLUE_NOT_FOUND",
                    "线索 rowId 不存在: " + rowId);
        }

        // 2. Extract phone from the CRM row (using known control ID)
        String phone = extractPhoneFromRow(row);
        if (!StringUtils.hasText(phone)) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "CLUE_PHONE_MISSING",
                    "线索缺少手机号, rowId=" + rowId);
        }
        phone = phone.trim();

        // 3. Get full LeadRecord by phone (reuses CRM-first parsing in ClueIntegrationService)
        Optional<LeadRecord> leadOpt = clueIntegrationService.lookupLeadByPhone(phone);
        if (leadOpt.isEmpty()) {
            // Fallback: re-query CRM directly for this rowId to handle edge cases
            log.warn("lookupLeadByPhone returned empty after rowId validation. phone={}, rowId={}", phone, rowId);
            throw new V2Exception(HttpStatus.BAD_REQUEST, "CLUE_NOT_FOUND",
                    "无法获取线索详情, rowId=" + rowId);
        }
        LeadRecord clue = leadOpt.get();

        // 4. Backfill resource.customerPhone if empty
        if (resource.getCustomerPhone() == null || resource.getCustomerPhone().isBlank()) {
            resource.setCustomerPhone(phone);
            resourceRepository.save(resource);
            log.info("Backfilled resource.customerPhone. resourceId={}, phone={}", id, phone);
        }

        // 5. Upsert lead_records
        try {
            LeadRecordEntity entity = leadRecordRepository.findByCustomerPhone(phone)
                    .orElseGet(LeadRecordEntity::new);
            entity.setCustomerPhone(phone);
            entity.setCrmRowId(rowId);
            entity.setLeadData(objectMapper.writeValueAsString(clue));
            entity.setLeadsType(clue.getLeadsType());
            entity.setLeadsStatus(clue.getLeadsStatus());
            entity.setCrmSyncedAt(Instant.now());
            leadRecordRepository.save(entity);
            log.info("Linked resource {} to clue rowId={}, phone={}", id, rowId, phone);
        } catch (Exception ex) {
            log.error("Failed to upsert lead_records. resourceId={}, rowId={}", id, rowId, ex);
            throw new V2Exception(HttpStatus.INTERNAL_SERVER_ERROR, "LINK_FAILED",
                    "绑定线索失败: " + ex.getMessage());
        }

        return Response.ok(Map.of("linked", true, "rowId", rowId));
    }

    /**
     * Extract phone from a CRM leads_bank row using the known control ID.
     */
    private String extractPhoneFromRow(JsonNode row) {
        // CLUE_PHONE controlId = 687fa4dd005dfd294df9dc3e
        JsonNode phoneNode = row.get("687fa4dd005dfd294df9dc3e");
        if (phoneNode == null || phoneNode.isNull()) return "";
        if (phoneNode.isTextual()) return phoneNode.asText();
        if (phoneNode.isArray() && phoneNode.size() > 0) return phoneNode.get(0).asText("");
        return phoneNode.asText();
    }

    /**
     * Create a new clue in CRM for this resource and auto-link it.
     * Uses resource's customerPhone and customerName as defaults.
     * After creation, a subsequent GET /crm-profile will return linked=true.
     */
    @PostMapping("/{id}/create-lead")
    public Response<Map<String, Object>> createLead(@PathVariable Long id) {
        AuthenticatedUser user = CurrentUser.get();
        ResourceEntity resource = service.get(id, user);
        String phone = resource.getCustomerPhone();

        if (!StringUtils.hasText(phone)) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "PHONE_MISSING",
                    "资源缺少手机号，无法创建线索");
        }
        phone = phone.trim();

        String contactName = resource.getCustomerName();
        String agentRowId = user.getAccountRowId();

        Optional<LeadRecord> created = clueIntegrationService.createLeadForNewCustomer(
                phone, contactName, agentRowId);

        if (created.isEmpty()) {
            throw new V2Exception(HttpStatus.INTERNAL_SERVER_ERROR, "CREATE_LEAD_FAILED",
                    "创建线索失败");
        }

        LeadRecord lead = created.get();
        return Response.ok(Map.of("linked", true, "rowId", lead.getRowId(), "clue", lead));
    }

}
