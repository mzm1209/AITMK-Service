package com.example.aitmk.service.impl;

import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.support.CrmRelationIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 CRM 坐席账号工作表读取坐席等级。
 * 不持有 {@code AgentDispatchService} 引用以避免循环依赖，
 * 调用方拿到 {@link Profile} 后自行调用 {@code setAgentProfile}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProfileSyncService {

    private static final String WORKSHEET = "imzhgl";
    private static final String LEVEL_FIELD = "69ca5415433ec9f4b5e7fced";
    private static final String LEVEL_WORKSHEET = "zxdjgl";
    private static final String LEVEL_NAME_FIELD = "69ca5008433ec9f4b5e7fc14";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CrmOpenApiService crm;
    private final Environment environment;
    private final ConcurrentHashMap<String, String> levelNameByRowId = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${agent.max-load:8}")
    private int maxLoad;

    public record Profile(String level, int maxLoad) {}

    /**
     * 从 CRM 查询坐席等级，返回等级和软负载上限。
     * 查询失败返回 null，不阻塞上线。
     */
    public Profile loadProfile(String agentRowId) {
        try {
            JsonNode root = crm.frontendGetFilterRows(WORKSHEET, List.of(), 200, 1, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) return null;
            for (JsonNode row : root.path("data").path("rows")) {
                String rid = row.path("rowid").asText("").trim();
                if (!rid.equals(agentRowId)) continue;
                JsonNode levelNode = row.path(LEVEL_FIELD);
                String level = extractLevel(levelNode);
                log.info("Agent level raw CRM value. agent={}, raw={}, resolvedLevel={}",
                        agentRowId, levelNode, level);
                if (!StringUtils.hasText(level)) return null;
                return new Profile(level, maxLoad);
            }
        } catch (Exception e) {
            log.warn("Failed to load agent profile from CRM. agent={}", agentRowId, e);
        }
        return null;
    }

    private String extractLevel(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        String display = displayText(value);
        if (StringUtils.hasText(display)) {
            log.info("Agent level resolved from display text. raw={}, display={}", value, display);
            return display;
        }
        List<String> ids = CrmRelationIds.parse(value);
        if (ids.isEmpty() && value.isTextual()) {
            ids = CrmRelationIds.parseText(value.asText());
        }
        if (ids.isEmpty()) return "";
        String levelRowId = ids.get(0);
        String configuredName = environment.getProperty("agent.level.id." + levelRowId);
        if (StringUtils.hasText(configuredName)) {
            String resolved = configuredName.trim();
            log.info("Agent level resolved from configured relation id. raw={}, levelRowId={}, resolvedLevel={}",
                    value, levelRowId, resolved);
            return resolved;
        }
        String resolved = resolveLevelName(levelRowId).orElse(levelRowId);
        log.info("Agent level resolved from relation id. raw={}, levelRowId={}, resolvedLevel={}",
                value, levelRowId, resolved);
        return resolved;
    }

    private String displayText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) {
            String text = value.asText("").trim();
            if (!StringUtils.hasText(text)) return "";
            if (looksLikeJson(text)) {
                try {
                    return displayText(JSON.readTree(text));
                } catch (Exception ignored) {
                    return "";
                }
            }
            return text;
        }
        if (value.isArray()) {
            List<String> values = new ArrayList<>();
            value.forEach(item -> {
                String text = displayText(item);
                if (StringUtils.hasText(text)) values.add(text);
            });
            return String.join(",", values);
        }
        if (value.isObject()) {
            for (String field : List.of("name", "fullname", "fullName", "text", "title", "levelName")) {
                String text = value.path(field).asText("").trim();
                if (StringUtils.hasText(text)) return text;
            }
            String rawValue = value.path("value").asText("").trim();
            if (StringUtils.hasText(rawValue) && !isRelationId(rawValue)) return rawValue;
        }
        return "";
    }

    private Optional<String> resolveLevelName(String levelRowId) {
        if (!StringUtils.hasText(levelRowId)) return Optional.empty();
        String cached = levelNameByRowId.get(levelRowId);
        if (StringUtils.hasText(cached)) return Optional.of(cached);
        try {
            JsonNode root = crm.frontendGetFilterRows(LEVEL_WORKSHEET, List.of(), 200, 1, 0, List.of());
            if (root == null || !root.path("success").asBoolean(false)) return Optional.empty();
            for (JsonNode row : root.path("data").path("rows")) {
                String rowId = row.path("rowid").asText("").trim();
                String name = displayText(row.path(LEVEL_NAME_FIELD));
                if (StringUtils.hasText(rowId) && StringUtils.hasText(name)) {
                    levelNameByRowId.put(rowId, name);
                    log.info("Agent level table mapping loaded. levelRowId={}, levelName={}", rowId, name);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve agent level name. levelRowId={}", levelRowId, e);
        }
        String name = levelNameByRowId.get(levelRowId);
        return StringUtils.hasText(name) ? Optional.of(name) : Optional.empty();
    }

    private boolean looksLikeJson(String text) {
        return text.startsWith("[") || text.startsWith("{") || text.startsWith("\"");
    }

    private boolean isRelationId(String value) {
        String text = value == null ? "" : value.trim();
        return text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        if (value.isArray() && !value.isEmpty()) return value.get(0).asText("");
        return "";
    }
}
