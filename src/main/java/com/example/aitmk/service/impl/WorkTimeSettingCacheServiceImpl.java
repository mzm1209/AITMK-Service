package com.example.aitmk.service.impl;

import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.WorkTimeSettingCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkTimeSettingCacheServiceImpl implements WorkTimeSettingCacheService {

    private static final String WORKSHEET_ID = "69fd6e76cd23604cb45f0950";
    private static final int MAX_FETCH_SIZE = 200;

    private final CrmOpenApiService crmOpenApiService;
    private final ObjectMapper objectMapper;
    private final AtomicReference<ArrayNode> cache = new AtomicReference<>();

    @PostConstruct
    public void init() {
        reload();
    }

    @Override
    public synchronized void reload() {
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, List.of(), MAX_FETCH_SIZE, 1, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) {
            log.warn("Load work time settings from CRM failed.");
            return;
        }

        JsonNode rows = root.path("data").path("rows");
        ArrayNode arrayNode = objectMapper.createArrayNode();
        if (rows.isArray()) {
            rows.forEach(arrayNode::add);
        }
        cache.set(arrayNode);
        log.info("Work time settings cache loaded. size={}", arrayNode.size());
    }

    @Override
    public JsonNode snapshot() {
        ArrayNode current = cache.get();
        if (current == null) {
            return objectMapper.createArrayNode();
        }
        return current.deepCopy();
    }
}
