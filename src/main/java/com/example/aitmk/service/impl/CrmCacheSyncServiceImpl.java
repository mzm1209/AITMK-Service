package com.example.aitmk.service.impl;

import com.example.aitmk.model.domain.AssignmentRecord;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.domain.CrmChatRecord;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CacheSyncService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    public void init() {
        syncFromCrm();
    }

    @Override
    public synchronized void syncFromCrm() {
        try {
            Set<String> onlineAgents = new LinkedHashSet<>(crmOpenApiService.listOnlineAgents());
            List<AssignmentRecord> assignments = crmOpenApiService.listAssignments();
            List<CrmChatRecord> chats = crmOpenApiService.listChatRecords();

            Map<String, String> assignmentMap = new HashMap<>();
            assignments.forEach(a -> assignmentMap.put(a.getCustomerPhone(), a.getAgentRowId()));
            agentDispatchService.replaceState(onlineAgents, assignmentMap);

            Map<String, List<ChatMessageRecord>> historyMap = new HashMap<>();
            for (CrmChatRecord c : chats) {
                historyMap.compute(c.getCustomerPhone(), (k, v) -> {
                    List<ChatMessageRecord> list = v == null ? new ArrayList<>() : v;
                    list.add(ChatMessageRecord.builder()
                            .customerId(c.getCustomerPhone())
                            .sender(c.getSender())
                            .message(c.getContent())
                            .timestamp(c.getSendTime())
                            .build());
                    return list;
                });
            }
            chatHistoryService.replaceAll(historyMap);
            log.info("CRM cache sync success. onlineAgents={}, assignments={}, customers={}",
                    onlineAgents.size(), assignmentMap.size(), historyMap.size());
        } catch (Exception e) {
            log.error("CRM cache sync failed", e);
        }
    }
}
