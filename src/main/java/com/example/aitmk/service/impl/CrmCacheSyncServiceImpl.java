package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.AssignmentRecord;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.CrmChatRecord;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CacheSyncService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRM -> 本地缓存同步服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmCacheSyncServiceImpl implements CacheSyncService {

    private final CrmOpenApiService crmOpenApiService;
    private final AgentDispatchService agentDispatchService;
    private final ChatHistoryService chatHistoryService;

    @Override
    public synchronized void syncFromCrm() {
        try {
            Set<String> onlineAgents = new LinkedHashSet<>(crmOpenApiService.listOnlineAgents());
            agentDispatchService.replaceState(onlineAgents, Map.of());
            log.debug("CRM presence sync success. onlineAgents={}; local database assignments/history were not overwritten", onlineAgents.size());
        } catch (Exception e) {
            log.warn("CRM cache sync failed. errorType={}", e.getClass().getSimpleName());
        }
    }
}
