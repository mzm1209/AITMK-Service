package com.example.aitmk.security.permission;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.JwtTokenService;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthRolePermissionAcceptanceTest {

    private final AgentDispatchService agentDispatchService = mock(AgentDispatchService.class);
    private final CrmOpenApiService crmOpenApiService = mock(CrmOpenApiService.class);
    private final ChatPermissionService permissionService = new ChatPermissionService(agentDispatchService, crmOpenApiService);

    @Test
    void jwtCarriesLoginIdentityRolePermissionsAndScope() {
        JwtTokenService tokenService = new JwtTokenService("acceptance-secret-acceptance-secret-123", 7200);
        CrmAgentAccount account = CrmAgentAccount.builder()
                .rowId("manager-1")
                .loginAccount("manager01")
                .relatedUserIds("user-a,user-b")
                .role(AgentRole.MANAGER)
                .managedAgentIds(List.of("agent-1", "agent-2"))
                .enabled(true)
                .build();

        AuthenticatedUser actual = tokenService.parseToken(tokenService.generateToken(account));

        assertThat(actual.getAccountRowId()).isEqualTo("manager-1");
        assertThat(actual.getLoginAccount()).isEqualTo("manager01");
        assertThat(actual.getRole()).isEqualTo(AgentRole.MANAGER);
        assertThat(actual.getRelatedUserIds()).isEqualTo("user-a,user-b");
        assertThat(actual.getManagedAgentIds()).containsExactly("agent-1", "agent-2");
        assertThat(actual.getPermissions()).contains(Permission.CHAT_VIEW_MANAGED, Permission.CHAT_JOIN_ANY)
                .doesNotContain(Permission.CHAT_VIEW_ALL);
    }

    @Test
    void ownerHasFullChatAndAccountManagementPermission() {
        AuthenticatedUser owner = user("owner-1", AgentRole.OWNER, List.of());

        assertThat(permissionService.canViewCustomer(owner, "customer-any")).isTrue();
        assertThat(permissionService.canReplyCustomer(owner, "customer-any")).isTrue();
        assertThat(permissionService.canJoinConversation(owner, "customer-any")).isTrue();
        assertThat(permissionService.canManageAccounts(owner)).isTrue();
        assertThat(permissionService.canManageAgentLevels(owner)).isTrue();
    }

    @Test
    void tmkCanViewAndReplyServingCustomer() {
        AuthenticatedUser tmk = user("agent-1", AgentRole.TMK, List.of());
        when(crmOpenApiService.listAgentCustomerServiceStatus("agent-1"))
                .thenReturn(Map.of("customer-serving", "服务中"));

        assertThat(permissionService.canViewCustomer(tmk, "customer-serving")).isTrue();
        assertThat(permissionService.canReplyCustomer(tmk, "customer-serving")).isTrue();
    }

    @Test
    void tmkCanViewButCannotReplyClosedCustomer() {
        AuthenticatedUser tmk = user("agent-1", AgentRole.TMK, List.of());
        when(crmOpenApiService.listAgentCustomerServiceStatus("agent-1"))
                .thenReturn(Map.of("customer-closed", "已关闭"));

        assertThat(permissionService.canViewCustomer(tmk, "customer-closed")).isTrue();
        assertThat(permissionService.canReplyCustomer(tmk, "customer-closed")).isFalse();
    }

    @Test
    void tmkCannotAccessUnrelatedCustomerOrManageAccounts() {
        AuthenticatedUser tmk = user("agent-1", AgentRole.TMK, List.of());
        when(crmOpenApiService.listAgentCustomerServiceStatus("agent-1")).thenReturn(Map.of());
        when(agentDispatchService.getAssignedAgent("customer-other")).thenReturn(Optional.of("agent-2"));

        assertThat(permissionService.canViewCustomer(tmk, "customer-other")).isFalse();
        assertThat(permissionService.canReplyCustomer(tmk, "customer-other")).isFalse();
        assertThat(permissionService.canJoinConversation(tmk, "customer-other")).isFalse();
        assertThat(permissionService.canManageAccounts(tmk)).isFalse();
    }

    @Test
    void managerWithEmptyScopeIsDeniedByDefault() {
        AuthenticatedUser manager = user("manager-1", AgentRole.MANAGER, List.of());

        assertThat(permissionService.canViewCustomer(manager, "customer-any")).isFalse();
        assertThat(permissionService.canReplyCustomer(manager, "customer-any")).isFalse();
        assertThat(permissionService.canJoinConversation(manager, "customer-any")).isFalse();
        assertThat(permissionService.canManageAccounts(manager)).isFalse();
        assertThat(permissionService.canManageAgentLevels(manager)).isTrue();
    }

    @Test
    void managerWithScopeCanOnlyAccessManagedAgentCustomers() {
        AuthenticatedUser manager = user("manager-1", AgentRole.MANAGER, List.of("agent-1"));
        when(crmOpenApiService.findServingAgentRowId("customer-managed")).thenReturn(Optional.of("agent-1"));
        when(crmOpenApiService.findServingAgentRowId("customer-unmanaged")).thenReturn(Optional.of("agent-2"));

        assertThat(permissionService.canViewCustomer(manager, "customer-managed")).isTrue();
        assertThat(permissionService.canReplyCustomer(manager, "customer-managed")).isTrue();
        assertThat(permissionService.canJoinConversation(manager, "customer-managed")).isTrue();
        assertThat(permissionService.canViewCustomer(manager, "customer-unmanaged")).isFalse();
        assertThat(permissionService.canReplyCustomer(manager, "customer-unmanaged")).isFalse();
    }

    @Test
    void unknownCrmRoleFallsBackToTmk() {
        assertThat(AgentRole.from("unknown-role")).isEqualTo(AgentRole.TMK);
        assertThat(Permission.defaultsFor(AgentRole.from("unknown-role")))
                .containsExactlyInAnyOrder(
                        Permission.CHAT_VIEW_OWN,
                        Permission.CHAT_VIEW_ASSIGNED,
                        Permission.CHAT_REPLY_ASSIGNED,
                        Permission.RESOURCE_ASSIGN);
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
