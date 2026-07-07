package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.LeadRecord;
import com.example.aitmk.model.entity.LeadRecordEntity;
import com.example.aitmk.repository.LeadRecordRepository;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CRM leads_bank integration service with local DB fallback.
 *
 * - Queries CRM first; on failure reads the local lead_records table.
 * - On create/update, writes CRM first; on failure writes local-only.
 * - TMK agent validation queries the imzhgl login worksheet (main credentials).
 * - All CRM failures are logged but never block agent assignment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClueIntegrationService {

    private final CrmOpenApiService crm;
    private final LeadRecordRepository leadRepo;
    private final ObjectMapper objectMapper;

    // ── Worksheet IDs ──
    private static final String CLUE_WORKSHEET_ID = "leads_bank";
    private static final String LOGIN_WORKSHEET_ID = "imzhgl";

    // ── leads_bank control IDs ──
    private static final String CLUE_PHONE                = "687fa4dd005dfd294df9dc3e";
    private static final String CLUE_PARENT_NAME          = "66bdb9a46e5c3bc8e0c7df9a";
    private static final String CLUE_STUDENT_NAME         = "66b1f86d9d2c721e325fac78";
    private static final String CLUE_CENTER               = "66eeb5b0f53d52846e007a35";
    private static final String CLUE_CONTACTED_STATUS     = "66b36b8cce042770da7218b0";
    private static final String CLUE_LEADS_STATUS         = "66b5e34a7e23d13674f24129";
    private static final String CLUE_LEADS_TYPE           = "681c86c01e19a610d7200418";
    private static final String CLUE_FIRST_CHANNEL        = "67d3f3f3286831392e292f7a";
    private static final String CLUE_PROGRAM_INTEREST     = "66b310829b545d2337ac4433";
    private static final String CLUE_SCHOOL               = "66b3692d3e774217ade72e25";
    private static final String CLUE_GRADE                = "66b30ef13e774217ade66e77";
    private static final String CLUE_CONTENT              = "6736e7c6f53d52846e00b0a3";
    private static final String CLUE_ASSIGN_TIME          = "66bb90bece042770da7b7041";
    private static final String CLUE_TMK                  = "68c252c0b75138cd755fb620";
    private static final String CLUE_FOLLOW_STAFF         = "66b3692d3e774217ade72e29";
    private static final String CLUE_LEADS_DATE           = "66c1e299666ad6264b6f5e15";
    private static final String CLUE_LATEST_ENTRY_CHANNEL = "69e0ca41433ec9f4b5e9720a";
    private static final String CLUE_LATEST_VISIT_CHANNEL = "69e0ca41433ec9f4b5e9720c";
    private static final String CLUE_VISIT_DATE           = "6836a4ef811c335bfbcdf342";
    private static final String CLUE_VISIT                = "68382b94811c335bfbcdf7ac";
    private static final String CLUE_VISIT_STATUS         = "683edab9811c335bfbce53eb";
    private static final String CLUE_PAY                  = "68383410811c335bfbcdf7c9";
    private static final String CLUE_PAYMENT_DATE         = "683832d8811c335bfbcdf7bf";
    private static final String CLUE_PAYMENT_AMOUNT       = "6836a787811c335bfbcdf35a";
    private static final String DEFAULT_LEADS_TYPE        = "Type D";

    // ── imzhgl control IDs ──
    private static final String LOGIN_RELATED_USER = "69abacc3433ec9f4b5e6ce26";
    private static final String LOGIN_ENABLED      = "6a322b23cd23604cb463cc08";

    // ── Channel sid ──
    private static final String CHANNEL_META_SID = "80f32937-d16d-4d82-8d0c-739b596cfb39";

    // ── Time format (same as CRM) ──
    private static final DateTimeFormatter CRM_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");

    // ═══════════════════════════════════════════════════════════
    //  Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * Look up lead by phone. CRM first, local DB fallback.
     * On CRM success the result is persisted to local DB.
     */
    public Optional<LeadRecord> lookupLeadByPhone(String phone) {
        if (!StringUtils.hasText(phone)) return Optional.empty();
        try {
            Optional<LeadRecord> crmLead = queryCrmLead(phone.trim());
            if (crmLead.isPresent()) {
                upsertLocalLead(phone.trim(), crmLead.get());
                return crmLead;
            }
        } catch (Exception ex) {
            log.warn("CRM lead lookup failed, trying local. phone={}", phone, ex);
        }
        return findLocalLead(phone.trim());
    }

    /** Read lead from local DB only (no CRM call). */
    public Optional<LeadRecord> findLocalLead(String phone) {
        if (!StringUtils.hasText(phone)) return Optional.empty();
        return leadRepo.findByCustomerPhone(phone.trim())
                .map(this::entityToLeadRecord);
    }

    /**
     * Check whether the TMK field points to a valid, enabled agent.
     * Returns the login record's rowId (used as agentRowId) if found.
     */
    public Optional<String> resolveTmkAgent(LeadRecord lead) {
        if (lead == null) return Optional.empty();
        Optional<String> accountId = lead.extractTmkAccountId();
        if (accountId.isEmpty()) return Optional.empty();

        try {
            // query all login records; typical deployment has < 200
            JsonNode root = crm.frontendGetFilterRows(
                    LOGIN_WORKSHEET_ID, List.of(), 200, 1, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) {
                return Optional.empty();
            }
            JsonNode rows = root.path("data").path("rows");
            if (!rows.isArray()) return Optional.empty();

            String targetId = accountId.get();
            for (JsonNode row : rows) {
                String enabled = extractText(row, LOGIN_ENABLED);
                if (!"启用".equals(enabled)) continue;
                String relatedUser = extractText(row, LOGIN_RELATED_USER);
                if (relatedUser.contains(targetId)) {
                    return Optional.of(row.path("rowid").asText(""));
                }
            }
        } catch (Exception ex) {
            log.warn("TMK agent resolve failed. accountId={}", accountId.get(), ex);
        }
        return Optional.empty();
    }

    /**
     * Reverse lookup: given a loginRowId (imzhgl rowid), return the corresponding
     * accountId used by CRM user-relation fields (e.g., TMK, 跟进员工).
     * Queries all imzhgl records and matches by rowid.
     */
    private Optional<String> resolveAccountIdFromLoginRowId(String loginRowId) {
        if (!StringUtils.hasText(loginRowId)) return Optional.empty();
        try {
            JsonNode root = crm.frontendGetFilterRows(
                    LOGIN_WORKSHEET_ID, List.of(), 200, 1, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) {
                return Optional.empty();
            }
            JsonNode rows = root.path("data").path("rows");
            if (!rows.isArray()) return Optional.empty();
            for (JsonNode row : rows) {
                if (loginRowId.equals(row.path("rowid").asText(""))) {
                    // LOGIN_RELATED_USER is a text field containing JSON array like [{"accountId":"xxx"}]
                    String rawRelated = row.path(LOGIN_RELATED_USER).asText("");
                    if (rawRelated.isBlank()) return Optional.empty();
                    return LeadRecord.extractAccountId(rawRelated);
                }
            }
        } catch (Exception ex) {
            log.warn("Account id resolve failed. loginRowId={}", loginRowId, ex);
        }
        return Optional.empty();
    }

    /**
     * Update existing lead: set TMK + 跟进员工 to assigned agent,
     * latest entry/visit channels to Meta.
     * CRM first; on failure, best-effort local update.
     */
    public void updateLeadOnAssignment(String rowId, String assignedAgentRowId) {
        if (!StringUtils.hasText(rowId) || !StringUtils.hasText(assignedAgentRowId)) return;
        try {
            doUpdateCrmLead(rowId, assignedAgentRowId);
            // re-fetch from CRM to keep local copy consistent
            String phone = findPhoneByRowId(rowId);
            if (phone != null) {
                try { queryCrmLead(phone).ifPresent(l -> upsertLocalLead(phone, l)); }
                catch (Exception ex) { log.warn("Re-fetch after update failed", ex); }
            }
        } catch (Exception ex) {
            log.warn("CRM lead update failed, update local only. rowId={}", rowId, ex);
            updateLocalLeadOnAssignment(rowId, assignedAgentRowId);
        }
    }

    /**
     * Create a new lead in CRM. CRM first; on failure create local-only.
     * @param phone       customer phone (required)
     * @param contactName WhatsApp profile name (fallback to phone if blank)
     * @param agentRowId  assigned agent's login rowId
     */
    public Optional<LeadRecord> createLeadForNewCustomer(
            String phone, String contactName, String agentRowId) {
        if (!StringUtils.hasText(phone)) return Optional.empty();
        try {
            Optional<LeadRecord> crmLead = doCreateCrmLead(phone.trim(), contactName, agentRowId);
            if (crmLead.isPresent()) {
                upsertLocalLead(phone.trim(), crmLead.get());
                return crmLead;
            }
        } catch (Exception ex) {
            log.warn("CRM lead create failed, create local only. phone={}", phone, ex);
        }
        LeadRecord localLead = buildLocalOnlyLead(phone.trim(), contactName, agentRowId);
        upsertLocalLead(phone.trim(), localLead);
        return Optional.of(localLead);
    }

    // ═══════════════════════════════════════════════════════════
    //  CRM operations
    // ═══════════════════════════════════════════════════════════

    private Optional<LeadRecord> queryCrmLead(String phone) {
        List<Map<String, Object>> filters = List.of(
                filter(CLUE_PHONE, phone, 3, 1, 2));
        JsonNode root = crm.frontendGetFilterRows(
                CLUE_WORKSHEET_ID, filters, 1, 1, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) return Optional.empty();
        JsonNode rows = root.path("data").path("rows");
        if (!rows.isArray() || rows.isEmpty()) return Optional.empty();
        return Optional.of(parseLeadRecord(rows.get(0)));
    }

    private void doUpdateCrmLead(String rowId, String loginRowId) {
        String accountId = resolveAccountIdFromLoginRowId(loginRowId).orElse(loginRowId);
        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(userRelationControl(CLUE_TMK, accountId));
        controls.add(userRelationControl(CLUE_FOLLOW_STAFF, accountId));
        controls.add(multiRelationControl(CLUE_LATEST_ENTRY_CHANNEL, CHANNEL_META_SID));
        controls.add(multiRelationControl(CLUE_LATEST_VISIT_CHANNEL, CHANNEL_META_SID));
        JsonNode root = crm.frontendEditRow(CLUE_WORKSHEET_ID, rowId, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            log.warn("CRM editRow returned failure. rowId={}", rowId);
        }
    }

    private Optional<LeadRecord> doCreateCrmLead(String phone, String contactName, String agentRowId) {
        String parentName = StringUtils.hasText(contactName) ? contactName.trim() : phone;
        String now = nowString();

        List<Map<String, Object>> controls = new ArrayList<>();
        controls.add(textControl(CLUE_PHONE, phone));
        controls.add(textControl(CLUE_PARENT_NAME, parentName));
        controls.add(dateControl(CLUE_LEADS_DATE, now));
        controls.add(selectControl(CLUE_LEADS_TYPE, DEFAULT_LEADS_TYPE));
        controls.add(dateControl(CLUE_ASSIGN_TIME, now));
        controls.add(userRelationControl(CLUE_TMK, resolveAccountIdFromLoginRowId(agentRowId).orElse(agentRowId)));
        controls.add(userRelationControl(CLUE_FOLLOW_STAFF, resolveAccountIdFromLoginRowId(agentRowId).orElse(agentRowId)));
        controls.add(multiRelationControl(CLUE_FIRST_CHANNEL, CHANNEL_META_SID));
        controls.add(multiRelationControl(CLUE_LATEST_ENTRY_CHANNEL, CHANNEL_META_SID));
        controls.add(multiRelationControl(CLUE_LATEST_VISIT_CHANNEL, CHANNEL_META_SID));

        JsonNode root = crm.frontendAddRow(CLUE_WORKSHEET_ID, controls, true);
        if (root == null || !root.path("success").asBoolean(false)) {
            log.warn("CRM addRow returned failure. phone={}", phone);
            return Optional.empty();
        }
        // re-query to get the full record with all CRM-generated fields
        return queryCrmLead(phone);
    }

    // ═══════════════════════════════════════════════════════════
    //  Local persistence
    // ═══════════════════════════════════════════════════════════

    private void upsertLocalLead(String phone, LeadRecord lead) {
        try {
            LeadRecordEntity entity = leadRepo.findByCustomerPhone(phone)
                    .orElseGet(LeadRecordEntity::new);
            entity.setCustomerPhone(phone);
            entity.setCrmRowId(lead.getRowId());
            entity.setLeadData(objectMapper.writeValueAsString(lead));
            entity.setLeadsType(lead.getLeadsType());
            entity.setLeadsStatus(lead.getLeadsStatus());
            entity.setCrmSyncedAt(Instant.now());
            leadRepo.save(entity);
        } catch (Exception ex) {
            log.error("Failed to upsert local lead record. phone={}", phone, ex);
        }
    }

    private void updateLocalLeadOnAssignment(String rowId, String agentRowId) {
        leadRepo.findByCrmRowId(rowId).ifPresent(entity -> {
            try {
                LeadRecord lead = objectMapper.readValue(entity.getLeadData(), LeadRecord.class);
                String agentJson = "[{\"accountId\":\"" + agentRowId + "\"}]";
                lead.setTmk(agentJson);
                entity.setLeadData(objectMapper.writeValueAsString(lead));
                entity.setLeadsType(lead.getLeadsType());
                entity.setLeadsStatus(lead.getLeadsStatus());
                leadRepo.save(entity);
            } catch (Exception ex) {
                log.warn("Local lead update failed. rowId={}", rowId, ex);
            }
        });
    }

    private LeadRecord buildLocalOnlyLead(String phone, String contactName, String agentRowId) {
        String parentName = StringUtils.hasText(contactName) ? contactName.trim() : phone;
        String agentJson = "[{\"accountId\":\"" + agentRowId + "\"}]";
        String metaJson = "[{\"sid\":\"" + CHANNEL_META_SID + "\"}]";
        String now = nowString();

        return LeadRecord.builder()
                .rowId(null)
                .phone(phone)
                .parentName(parentName)
                .leadsDate(now)
                .leadsType(DEFAULT_LEADS_TYPE)
                .assignedTime(now)
                .tmk(agentJson)
                .firstCreatChannel(metaJson)
                .build();
    }

    private String findPhoneByRowId(String rowId) {
        return leadRepo.findByCrmRowId(rowId)
                .map(LeadRecordEntity::getCustomerPhone).orElse(null);
    }

    // ═══════════════════════════════════════════════════════════
    //  Parsing
    // ═══════════════════════════════════════════════════════════

    private LeadRecord parseLeadRecord(JsonNode row) {
        return LeadRecord.builder()
                .rowId(row.path("rowid").asText(""))
                .leadsDate(rawValue(row, CLUE_LEADS_DATE))
                .parentName(extractText(row, CLUE_PARENT_NAME))
                .studentName(extractText(row, CLUE_STUDENT_NAME))
                .phone(extractText(row, CLUE_PHONE))
                .center(rawValue(row, CLUE_CENTER))
                .contactedStatus(extractText(row, CLUE_CONTACTED_STATUS))
                .leadsStatus(extractText(row, CLUE_LEADS_STATUS))
                .leadsType(extractText(row, CLUE_LEADS_TYPE))
                .firstCreatChannel(rawValue(row, CLUE_FIRST_CHANNEL))
                .programInterest(extractText(row, CLUE_PROGRAM_INTEREST))
                .school(extractText(row, CLUE_SCHOOL))
                .grade(extractText(row, CLUE_GRADE))
                .content(extractText(row, CLUE_CONTENT))
                .assignedTime(rawValue(row, CLUE_ASSIGN_TIME))
                .tmk(extractText(row, CLUE_TMK))
                .visitDate(rawValue(row, CLUE_VISIT_DATE))
                .visit(extractText(row, CLUE_VISIT))
                .visitStatus(extractText(row, CLUE_VISIT_STATUS))
                .pay(extractText(row, CLUE_PAY))
                .paymentDate(rawValue(row, CLUE_PAYMENT_DATE))
                .paymentAmount(rawValue(row, CLUE_PAYMENT_AMOUNT))
                .build();
    }

    private LeadRecord entityToLeadRecord(LeadRecordEntity entity) {
        try {
            LeadRecord lead = objectMapper.readValue(entity.getLeadData(), LeadRecord.class);
            if (lead.getRowId() == null) lead.setRowId(entity.getCrmRowId());
            return lead;
        } catch (Exception ex) {
            log.error("Failed to deserialize lead data from local DB", ex);
            return LeadRecord.builder()
                    .rowId(entity.getCrmRowId())
                    .phone(entity.getCustomerPhone())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CRM control factory methods
    // ═══════════════════════════════════════════════════════════

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

    private static Map<String, Object> textControl(String controlId, String value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
    }

    private static Map<String, Object> selectControl(String controlId, String value) {
        Map<String, Object> item = textControl(controlId, value);
        item.put("valueType", 2);
        return item;
    }

    private static Map<String, Object> dateControl(String controlId, String value) {
        return textControl(controlId, value);
    }

   private static Map<String, Object> userRelationControl(String controlId, String accountId) {
        // type=26 user relation fields accept plain accountId string
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", accountId);
        return item;
   }

    private static Map<String, Object> multiRelationControl(String controlId, String sid) {
        // type=35 multi-relation fields accept plain sid string
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", sid);
        return item;
    }

    // ═══════════════════════════════════════════════════════════
    //  Utilities
    // ═══════════════════════════════════════════════════════════

    private static String extractText(JsonNode row, String fieldName) {
        JsonNode node = row.get(fieldName);
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.has("accountId")) return first.path("accountId").asText("");
            if (first.has("name")) return first.path("name").asText("");
            return first.asText();
        }
        return node.asText();
    }

    private static Object rawValue(JsonNode row, String fieldName) {
        JsonNode node = row.get(fieldName);
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        return node;  // pass through object/array for frontend
    }

    private static String nowString() {
        return LocalDateTime.now().format(CRM_TIME_FORMAT);
    }
}
