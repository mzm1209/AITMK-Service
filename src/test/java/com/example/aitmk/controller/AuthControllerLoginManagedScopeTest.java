package com.example.aitmk.controller;

import com.example.aitmk.model.domain.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.*;
import com.example.aitmk.service.impl.AgentSessionActivityService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthControllerLoginManagedScopeTest {
    @Test void loginResponseReturnsCrmManagedAgentIds() {
        CrmOpenApiService crm=mock(CrmOpenApiService.class);
        AgentDispatchService dispatch=mock(AgentDispatchService.class);
        AgentPushService push=mock(AgentPushService.class);
        ChatHistoryService history=mock(ChatHistoryService.class);
        CacheSyncService cache=mock(CacheSyncService.class);
        AgentSessionActivityService sessions=mock(AgentSessionActivityService.class);
        JwtTokenService tokens=new JwtTokenService("login-response-test-secret-32-bytes-long",3600);
        AuthController controller=new AuthController(crm,dispatch,push,history,cache,sessions,tokens);
        when(crm.verifyLogin(anyString(),anyString())).thenReturn(Optional.of(CrmAgentAccount.builder()
                .rowId("manager-1").loginAccount("manager").role(AgentRole.MANAGER)
                .managedAgentIds(List.of("tmk-1","tmk-2")).enabled(true).build()));
        when(crm.findActiveLoginRecordRowId("manager-1")).thenReturn(Optional.of("login-row"));
        LoginRequest request=new LoginRequest();request.setUsername("manager");request.setPassword("redacted");

        LoginResponse response=controller.login(request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getManagedAgentIds()).containsExactly("tmk-1","tmk-2");
        assertThat(tokens.parseToken(response.getAccessToken()).getManagedAgentIds()).containsExactly("tmk-1","tmk-2");
    }

    @Test void emptyCrmRelationStaysEmptyInLoginResponseAndJwt() {
        CrmOpenApiService crm=mock(CrmOpenApiService.class);
        AgentDispatchService dispatch=mock(AgentDispatchService.class);
        AgentSessionActivityService sessions=mock(AgentSessionActivityService.class);
        JwtTokenService tokens=new JwtTokenService("empty-login-response-test-secret-32-bytes",3600);
        AuthController controller=new AuthController(crm,dispatch,mock(AgentPushService.class),mock(ChatHistoryService.class),
                mock(CacheSyncService.class),sessions,tokens);
        when(crm.verifyLogin(anyString(),anyString())).thenReturn(Optional.of(CrmAgentAccount.builder()
                .rowId("manager-empty").loginAccount("manager-empty").role(AgentRole.MANAGER)
                .managedAgentIds(List.of()).enabled(true).build()));
        when(crm.findActiveLoginRecordRowId("manager-empty")).thenReturn(Optional.of("login-row"));
        LoginRequest request=new LoginRequest();request.setUsername("manager-empty");request.setPassword("redacted");

        LoginResponse response=controller.login(request).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getManagedAgentIds()).isEmpty();
        assertThat(tokens.parseToken(response.getAccessToken()).getManagedAgentIds()).isEmpty();
    }
}
