package com.example.aitmk.service;

import com.example.aitmk.model.api.v2.V2Api.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached CRM worksheet field configuration service.
 *
 * - Calls CRM getWorksheetInfo on first access per worksheetId.
 * - Cache TTL: 3600s (field definitions change rarely).
 * - On CRM failure, falls back to hardcoded leads_bank control definitions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorksheetFieldService {

    private final CrmOpenApiService crm;

    @Value("${crm.worksheet-field-whitelist:leads_bank}")
    private String whitelistRaw;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final Duration TTL = Duration.ofHours(1);

    private record CacheEntry(WorksheetFieldsView view, long loadedAtMs) {}

    // ── Hardcoded fallback for leads_bank (known 21 controlIds) ──
    private static final List<FieldConfigView> LEADS_BANK_HARDCODED = List.of(
            field("66c1e299666ad6264b6f5e15", "线索日期", 16, List.of()),
            field("66bdb9a46e5c3bc8e0c7df9a", "家长姓名", 2, List.of()),
            field("66b1f86d9d2c721e325fac78", "孩子姓名", 2, List.of()),
            field("687fa4dd005dfd294df9dc3e", "手机号", 3, List.of()),
            field("66eeb5b0f53d52846e007a35", "校区", 27, List.of()),
            field("66b36b8cce042770da7218b0", "联系状态", 11, List.of()),
            field("66b5e34a7e23d13674f24129", "线索状态", 11, List.of()),
            field("681c86c01e19a610d7200418", "线索类型", 11, List.of()),
            field("67d3f3f3286831392e292f7a", "首次录入渠道", 35, List.of()),
            field("66b310829b545d2337ac4433", "意向科目", 11, List.of()),
            field("66b3692d3e774217ade72e25", "学校", 2, List.of()),
            field("66b30ef13e774217ade66e77", "年级", 11, List.of()),
            field("6736e7c6f53d52846e00b0a3", "备注", 2, List.of()),
            field("66bb90bece042770da7b7041", "线索分配时间", 16, List.of()),
            field("68c252c0b75138cd755fb620", "TMK", 26, List.of()),
            field("6836a4ef811c335bfbcdf342", "到访日期", 16, List.of()),
            field("68382b94811c335bfbcdf7ac", "是否已到访", 11, List.of()),
            field("683edab9811c335bfbce53eb", "到访状态", 11, List.of()),
            field("68383410811c335bfbcdf7c9", "是否已成交", 11, List.of()),
            field("683832d8811c335bfbcdf7bf", "成交日期", 16, List.of()),
            field("6836a787811c335bfbcdf35a", "成交金额", 8, List.of())
    );

    private static FieldConfigView field(String controlId, String controlName, int dataType, List<FieldOption> options) {
        return new FieldConfigView(controlId, controlName, dataType, options);
    }

    /**
     * Get field definitions for a worksheet.
     * CRM call is cached with TTL; on CRM failure returns hardcoded fallback.
     */
    public WorksheetFieldsView getFields(String worksheetId) {
        validateWorksheetId(worksheetId);

        CacheEntry entry = cache.get(worksheetId);
        if (entry != null && System.currentTimeMillis() - entry.loadedAtMs < TTL.toMillis()) {
            return entry.view;
        }

        WorksheetFieldsView view = loadFromCrm(worksheetId);
        cache.put(worksheetId, new CacheEntry(view, System.currentTimeMillis()));
        return view;
    }

    /**
     * Get fieldsConfig map for crm-profile response.
     * Returns { controlName: { controlId, type, label, options } }.
     */
    public Map<String, Map<String, Object>> getFieldsConfig(String worksheetId) {
        WorksheetFieldsView view = getFields(worksheetId);
        Map<String, Map<String, Object>> config = new LinkedHashMap<>();
        for (FieldConfigView f : view.fields()) {
            Map<String, Object> fieldMap = new LinkedHashMap<>();
            fieldMap.put("controlId", f.controlId());
            fieldMap.put("type", f.dataType());
            fieldMap.put("label", f.controlName());
            List<String> optionValues = new ArrayList<>();
            for (FieldOption opt : f.options()) {
                optionValues.add(opt.value());
            }
            fieldMap.put("options", optionValues);
            config.put(f.controlName(), fieldMap);
        }
        return config;
    }

    private WorksheetFieldsView loadFromCrm(String worksheetId) {
        try {
            JsonNode root = crm.getWorksheetInfo(worksheetId);
            if (root != null && root.path("success").asBoolean(false)) {
                return parseWorksheetInfo(worksheetId, root);
            }
        } catch (Exception ex) {
            log.warn("CRM getWorksheetInfo failed, using hardcoded fallback. worksheetId={}", worksheetId, ex);
        }
        return fallback(worksheetId);
    }

    private WorksheetFieldsView fallback(String worksheetId) {
        if ("leads_bank".equals(worksheetId)) {
            return new WorksheetFieldsView(worksheetId, "线索管理(离线)", LEADS_BANK_HARDCODED);
        }
        return new WorksheetFieldsView(worksheetId, worksheetId + "(离线)", List.of());
    }

    private WorksheetFieldsView parseWorksheetInfo(String worksheetId, JsonNode root) {
        String worksheetName = root.path("data").path("worksheetName").asText(worksheetId);
        JsonNode controls = root.path("data").path("controls");
        List<FieldConfigView> fields = new ArrayList<>();

        if (controls.isArray()) {
            for (JsonNode ctrl : controls) {
                String controlId = ctrl.path("controlId").asText("");
                String controlName = ctrl.path("controlName").asText("");
                int dataType = ctrl.path("dataType").asInt(0);

                List<FieldOption> options = new ArrayList<>();
                JsonNode opts = ctrl.path("options");
                if (opts.isArray()) {
                    for (JsonNode opt : opts) {
                        String key = opt.path("key").asText(opt.path("value").asText(""));
                        String value = opt.path("value").asText("");
                        if (!key.isEmpty() || !value.isEmpty()) {
                            options.add(new FieldOption(key, value));
                        }
                    }
                }
                fields.add(new FieldConfigView(controlId, controlName, dataType, options));
            }
        }
        return new WorksheetFieldsView(worksheetId, worksheetName, fields);
    }

    private void validateWorksheetId(String worksheetId) {
        if (worksheetId == null || worksheetId.isBlank()) {
            throw new IllegalArgumentException("worksheetId is required");
        }
        Set<String> whitelist = new HashSet<>();
        for (String s : whitelistRaw.split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) whitelist.add(trimmed);
        }
        if (!whitelist.contains(worksheetId)) {
            throw new com.example.aitmk.model.api.v2.V2Exception(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "WORKSHEET_NOT_ALLOWED",
                    "工作表不在白名单内: " + worksheetId);
        }
    }
}
