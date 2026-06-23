package com.example.aitmk.service.impl;

import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ChatHistoryService;
import com.example.aitmk.service.CrmOpenApiService;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class CrmCacheSyncServiceImplTest {

    @Test
    void syncImportsPresenceOnlyAndNeverOverwritesLocalBusinessData() {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        AgentDispatchService dispatch = mock(AgentDispatchService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(crm.listOnlineAgents()).thenReturn(Set.of("agent-a", "agent-b"));
        CrmCacheSyncServiceImpl service = new CrmCacheSyncServiceImpl(crm, dispatch, history);

        service.syncFromCrm();

        verify(dispatch).replaceState(Set.of("agent-a", "agent-b"), Map.of());
        verify(crm, never()).listAssignments();
        verify(crm, never()).listChatRecords();
        verify(history, never()).replaceAll(anyMap());
    }

    @Test
    void crmFailureIsContainedAndDoesNotMutateLocalState() {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        AgentDispatchService dispatch = mock(AgentDispatchService.class);
        ChatHistoryService history = mock(ChatHistoryService.class);
        when(crm.listOnlineAgents()).thenThrow(new IllegalStateException("CRM unavailable"));
        CrmCacheSyncServiceImpl service = new CrmCacheSyncServiceImpl(crm, dispatch, history);

        assertThatCode(service::syncFromCrm).doesNotThrowAnyException();
        verifyNoInteractions(dispatch, history);
    }
}
