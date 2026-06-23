package com.example.aitmk.service.impl;

import com.example.aitmk.service.AutoReplyScriptCacheService;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReplyScriptCacheServiceImpl implements AutoReplyScriptCacheService {

    private static final String WORKSHEET_ID = "69fd717fcd23604cb45f097b";
    private static final String FIRST_REPLY_CONTROL_ID = "69fd717fcd23604cb45f097d";

    private final CrmOpenApiService crmOpenApiService;
    private final ObjectMapper objectMapper;
    private final AtomicReference<ArrayNode> cache = new AtomicReference<>();
    @Value("${crm.bootstrap-enabled:true}")
    private boolean bootstrapEnabled;

    @PostConstruct
    public void init() {
        if (bootstrapEnabled) reload();
    }

    @Override
    public synchronized void reload() {
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, List.of(), 200, 1, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            log.warn("Load auto reply scripts from CRM failed.");
            return;
        }
        ArrayNode arr = objectMapper.createArrayNode();
        JsonNode rows = root.path("data").path("rows");
        if (rows.isArray()) rows.forEach(arr::add);
        cache.set(arr);
        log.info("Auto reply scripts cache loaded. size={}", arr.size());
    }

    @Override
    public JsonNode snapshot() {
        ArrayNode current = cache.get();
        return current == null ? objectMapper.createArrayNode() : current.deepCopy();
    }

    @Override
    public String firstReplyScript() {
        JsonNode rows = cache.get();
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        String text = rows.get(0).path(FIRST_REPLY_CONTROL_ID).asText("");
        return text == null ? "" : text.trim();
    }
}
