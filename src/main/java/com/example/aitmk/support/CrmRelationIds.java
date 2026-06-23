package com.example.aitmk.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/** CRM Relation(dataType=29) rowId 的唯一解析与写入规则。 */
public final class CrmRelationIds {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ID_FIELDS = List.of("sid", "rowid", "rowId", "id", "accountId");

    private CrmRelationIds() {}

    public static List<String> parse(JsonNode value) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collect(value, ids);
        return List.copyOf(ids);
    }

    public static List<String> parseText(String value) {
        if (value == null) return List.of();
        String text = value.trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "[]".equals(text) || "{}".equals(text)) return List.of();
        if (looksLikeJson(text)) {
            try {
                return parse(JSON.readTree(text));
            } catch (Exception ignored) {
                // JSON 外形但格式异常时 fail closed，禁止降级为逗号拆分。
                return List.of();
            }
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Arrays.stream(text.split(",", -1)).map(String::trim).filter(CrmRelationIds::isPlainId).forEach(ids::add);
        return List.copyOf(ids);
    }

    public static String serialize(List<String> rowIds) {
        if (rowIds == null) return "";
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        rowIds.stream().filter(Objects::nonNull).map(String::trim).filter(CrmRelationIds::isPlainId).forEach(ids::add);
        return String.join(",", ids);
    }

    private static void collect(JsonNode value, Set<String> ids) {
        if (value == null || value.isMissingNode() || value.isNull()) return;
        if (value.isTextual()) {
            ids.addAll(parseText(value.asText()));
            return;
        }
        if (value.isArray()) {
            value.forEach(item -> collect(item, ids));
            return;
        }
        if (value.isObject()) {
            for (String field : ID_FIELDS) {
                JsonNode id = value.get(field);
                if (id != null && id.isValueNode() && isPlainId(id.asText())) {
                    ids.add(id.asText().trim());
                    return;
                }
            }
        }
    }

    private static boolean looksLikeJson(String text) {
        return text.startsWith("[") || text.startsWith("{") || text.startsWith("\"");
    }

    private static boolean isPlainId(String value) {
        if (value == null) return false;
        String id = value.trim();
        return !id.isEmpty() && !"null".equalsIgnoreCase(id) && !"[]".equals(id) && !"{}".equals(id)
                && !id.contains(",") && !id.startsWith("[") && !id.startsWith("{") && !id.startsWith("\"");
    }
}
