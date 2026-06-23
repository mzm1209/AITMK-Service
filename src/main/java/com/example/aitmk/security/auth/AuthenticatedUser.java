package com.example.aitmk.security.auth;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
@Builder
public class AuthenticatedUser {

    private String accountRowId;
    private String loginAccount;
    private AgentRole role;
    private Set<Permission> permissions;
    @Builder.Default
    private List<String> managedAgentIds = List.of();
    private String relatedUserIds;

    public boolean hasRole(AgentRole target) {
        return role == target;
    }

    public boolean hasPermission(Permission permission) {
        return permissions != null && permissions.contains(permission);
    }
}
