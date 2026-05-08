package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AutoReplyScriptUpsertRequest;
import com.example.aitmk.service.AutoReplyScriptCacheService;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auto-reply-scripts")
@RequiredArgsConstructor
public class AutoReplyScriptController {

    private static final String WORKSHEET_ID = "69fd717fcd23604cb45f097b";
    private static final String FIRST_REPLY_CONTROL_ID = "69fd717fcd23604cb45f097d";

    private final CrmOpenApiService crmOpenApiService;
    private final AutoReplyScriptCacheService cacheService;

    @PostMapping
    public ResponseEntity<?> add(@Valid @RequestBody AutoReplyScriptUpsertRequest request) {
        JsonNode root = crmOpenApiService.frontendAddRow(WORKSHEET_ID, List.of(control(FIRST_REPLY_CONTROL_ID, request.getFirstReply())), true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false));
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true, "rowId", root.path("data").asText("")));
    }

    @PutMapping("/{rowId}")
    public ResponseEntity<?> edit(@PathVariable String rowId, @Valid @RequestBody AutoReplyScriptUpsertRequest request) {
        if (!StringUtils.hasText(rowId)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        JsonNode root = crmOpenApiService.frontendEditRow(WORKSHEET_ID, rowId, List.of(control(FIRST_REPLY_CONTROL_ID, request.getFirstReply())), true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false));
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @DeleteMapping("/{rowId}")
    public ResponseEntity<?> delete(@PathVariable String rowId) {
        if (!StringUtils.hasText(rowId)) return ResponseEntity.badRequest().body(Map.of("success", false, "message", "rowId 不能为空"));
        JsonNode root = crmOpenApiService.frontendDeleteRow(WORKSHEET_ID, rowId, true);
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false));
        cacheService.reload();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(defaultValue = "50") int pageSize, @RequestParam(defaultValue = "1") int pageIndex) {
        JsonNode root = crmOpenApiService.frontendGetFilterRows(WORKSHEET_ID, List.of(), pageSize, pageIndex, 0, List.of());
        if (root == null || !root.path("success").asBoolean(false)) return ResponseEntity.badRequest().body(Map.of("success", false));
        return ResponseEntity.ok(Map.of("success", true, "total", root.path("data").path("total").asInt(0), "rows", root.path("data").path("rows")));
    }

    @GetMapping("/cache")
    public ResponseEntity<?> cache() {
        return ResponseEntity.ok(Map.of("success", true, "rows", cacheService.snapshot(), "firstReply", cacheService.firstReplyScript()));
    }

    private Map<String, Object> control(String controlId, Object value) {
        Map<String, Object> item = new HashMap<>();
        item.put("controlId", controlId);
        item.put("value", value);
        return item;
    }
}
