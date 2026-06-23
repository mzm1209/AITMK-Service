package com.example.aitmk.security.permission;

import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.AssignmentPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ChatPermissionServiceTest {

    private final AgentDispatchService agentDispatchService = mock(AgentDispatchService.class);
    private final CrmOpenApiService crmOpenApiService = mock(CrmOpenApiService.class);
    private final ChatPermissionService permissionService = new ChatPermissionService(agentDispatchService, crmOpenApiService);

    @Test
    void ownerCanViewAndReplyAnyCustomer() {
        AuthenticatedUser owner = user("owner-1", AgentRole.OWNER, List.of());

        assertThat(permissionService.canViewCustomer(owner, "customer-1")).isTrue();
        assertThat(permissionService.canReplyCustomer(owner, "customer-1")).isTrue();
        assertThat(permissionService.canManageAccounts(owner)).isTrue();
    }

    @Test
    void tmkCanReplyServingCustomerOnly() {
        AuthenticatedUser tmk = user("agent-1", AgentRole.TMK, List.of());
        when(crmOpenApiService.listAgentCustomerServiceStatus("agent-1"))
                .thenReturn(Map.of("serving-customer", "服务中", "closed-customer", "已关闭"));

        assertThat(permissionService.canViewCustomer(tmk, "serving-customer")).isTrue();
        assertThat(permissionService.canReplyCustomer(tmk, "serving-customer")).isTrue();
        assertThat(permissionService.canViewCustomer(tmk, "closed-customer")).isTrue();
        assertThat(permissionService.canReplyCustomer(tmk, "closed-customer")).isFalse();
        assertThat(permissionService.canViewCustomer(tmk, "other-customer")).isFalse();
    }

    @Test
    void managerCanViewManagedAgentCustomer() {
        AuthenticatedUser manager = user("manager-1", AgentRole.MANAGER, List.of("agent-2"));
        when(crmOpenApiService.findServingAgentRowId("customer-1")).thenReturn(Optional.of("agent-2"));
        when(crmOpenApiService.findServingAgentRowId("customer-2")).thenReturn(Optional.of("agent-3"));

        assertThat(permissionService.canViewCustomer(manager, "customer-1")).isTrue();
        assertThat(permissionService.canReplyCustomer(manager, "customer-1")).isTrue();
        assertThat(permissionService.canViewCustomer(manager, "customer-2")).isFalse();
    }

    @Test
    void persistentPermissionUsesLocalCurrentAndHistoricalAssignmentsWithoutCrmFallback() {
        AgentDispatchService localDispatch = mock(AgentDispatchService.class);
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        AssignmentPersistenceService assignments = mock(AssignmentPersistenceService.class);
        ChatPermissionService persistent = new ChatPermissionService(localDispatch, crm, assignments);
        AuthenticatedUser tmk = user("agent-local", AgentRole.TMK, List.of());
        when(localDispatch.getAssignedAgent("serving")).thenReturn(Optional.of("agent-local"));
        when(localDispatch.getAssignedAgent("closed")).thenReturn(Optional.empty());
        when(assignments.hasServed("closed", "agent-local")).thenReturn(true);

        assertThat(persistent.canViewCustomer(tmk, "serving")).isTrue();
        assertThat(persistent.canReplyCustomer(tmk, "serving")).isTrue();
        assertThat(persistent.canViewCustomer(tmk, "closed")).isTrue();
        assertThat(persistent.canReplyCustomer(tmk, "closed")).isFalse();
        assertThat(persistent.canViewCustomer(null, "serving")).isFalse();
        verifyNoInteractions(crm);
    }

    @Test
    void scopedManagerCannotUseClientSuppliedAgentToEscapeManagementScope() {
        AssignmentPersistenceService assignments = mock(AssignmentPersistenceService.class);
        ChatPermissionService persistent = new ChatPermissionService(agentDispatchService, crmOpenApiService, assignments);
        AuthenticatedUser manager = user("manager", AgentRole.MANAGER, List.of("managed-agent"));
        when(agentDispatchService.getAssignedAgent("managed-customer")).thenReturn(Optional.of("managed-agent"));
        when(agentDispatchService.getAssignedAgent("foreign-customer")).thenReturn(Optional.of("foreign-agent"));

        assertThat(persistent.canViewCustomer(manager, "managed-customer")).isTrue();
        assertThat(persistent.canViewCustomer(manager, "foreign-customer")).isFalse();
        assertThat(persistent.resolvePermittedAgent(manager, "foreign-agent")).isEqualTo("manager");
    }

    private AuthenticatedUser user(String accountRowId, AgentRole role, List<String> managedAgentIds) {
        return AuthenticatedUser.builder()
                .accountRowId(accountRowId)
                .loginAccount(accountRowId)
                .role(role)
                .permissions(Set.copyOf(Permission.defaultsFor(role)))
                .managedAgentIds(managedAgentIds)
                .relatedUserIds("")
                .build();
    }
}
