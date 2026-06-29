package com.example.aitmk.model.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import java.util.Optional;

/**
 * Domain model for CRM leads_bank worksheet record (21 fields).
 * Fields store CRM raw values to preserve original format for frontend delivery.
 */
@Data
@Builder
public class LeadRecord {

    private String rowId;

    // ── Basic info (13 fields) ──
    private Object leadsDate;        // 66c1e299666ad6264b6f5e15 线索日期
    private String parentName;       // 66bdb9a46e5c3bc8e0c7df9a 家长姓名
    private String studentName;      // 66b1f86d9d2c721e325fac78 孩子姓名
    private String phone;            // 687fa4dd005dfd294df9dc3e 手机号
    private Object center;           // 66eeb5b0f53d52846e007a35 校区
    private String contactedStatus;  // 66b36b8cce042770da7218b0 联系状态
    private String leadsStatus;      // 66b5e34a7e23d13674f24129 线索状态
    private String leadsType;        // 681c86c01e19a610d7200418 线索类型
    private Object firstCreatChannel;// 67d3f3f3286831392e292f7a 首次录入渠道
    private String programInterest;  // 66b310829b545d2337ac4433 意向科目
    private String school;           // 66b3692d3e774217ade72e25 学校
    private String grade;            // 66b30ef13e774217ade66e77 年级
    private String content;          // 6736e7c6f53d52846e00b0a3 备注

    // ── TMK assignment (2 fields) ──
    private Object assignedTime;     // 66bb90bece042770da7b7041 线索分配时间
    private String tmk;              // 68c252c0b75138cd755fb620 TMK (user rel JSON string)

    // ── Visit / Payment (6 fields) ──
    private Object visitDate;        // 6836a4ef811c335bfbcdf342 到访日期
    private String visit;            // 68382b94811c335bfbcdf7ac 是否已到访
    private String visitStatus;      // 683edab9811c335bfbce53eb 到访状态
    private String pay;              // 68383410811c335bfbcdf7c9 是否已成交
    private Object paymentDate;      // 683832d8811c335bfbcdf7bf 成交日期
    private Object paymentAmount;    // 6836a787811c335bfbcdf35a 成交金额

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean hasTmk() {
        return tmk != null && !tmk.isEmpty() && !"[]".equals(tmk);
    }

    public Optional<String> extractTmkAccountId() {
        if (!hasTmk()) return Optional.empty();
        try {
            JsonNode arr = MAPPER.readTree(tmk);
            if (arr.isArray() && arr.size() > 0) {
                JsonNode first = arr.get(0);
                String accountId = first.path("accountId").asText("");
                return accountId.isBlank() ? Optional.empty() : Optional.of(accountId);
            }
        } catch (Exception ignored) {
            // malformed JSON, treat as no TMK
        }
        return Optional.empty();
    }

    /** Extract the first accountId from a user-relation JSON string field. */
    public static Optional<String> extractAccountId(String userRelationJson) {
        if (userRelationJson == null || userRelationJson.isEmpty() || "[]".equals(userRelationJson))
            return Optional.empty();
        try {
            JsonNode arr = MAPPER.readTree(userRelationJson);
            if (arr.isArray() && arr.size() > 0) {
                return Optional.of(arr.get(0).path("accountId").asText(""));
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }
}
