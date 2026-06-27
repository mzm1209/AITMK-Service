package com.example.aitmk.security.permission;

import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.service.AssignmentPersistenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChatPermissionService {

    private final AgentDispatchService agentDispatchService;
    private final CrmOpenApiService crmOpenApiService;
    private final AssignmentPersistenceService assignmentPersistenceService;
    private final boolean legacyCrmFallback;

    @Autowired
    public ChatPermissionService(AgentDispatchService agentDispatchService, CrmOpenApiService crmOpenApiService,
                                 AssignmentPersistenceService assignmentPersistenceService) {
        this.agentDispatchService = agentDispatchService;
        this.crmOpenApiService = crmOpenApiService;
        this.assignmentPersistenceService = assignmentPersistenceService;
        this.legacyCrmFallback = false;
    }

    /** Backward-compatible constructor for existing isolated permission tests. */
    public ChatPermissionService(AgentDispatchService agentDispatchService, CrmOpenApiService crmOpenApiService) {
        this.agentDispatchService = agentDispatchService;
        this.crmOpenApiService = crmOpenApiService;
        this.assignmentPersistenceService = new AssignmentPersistenceService() {
            public Optional<String> currentAgent(String customerPhone) { return agentDispatchService.getAssignedAgent(customerPhone); }
            public Map<String, String> currentAssignments() { return agentDispatchService.assignmentsSnapshot(); }
            public boolean hasServed(String customerPhone, String agentId) { return false; }
        };
        this.legacyCrmFallback = true;
    }

    public boolean canViewCustomer(AuthenticatedUser user, String customerId) {
        if (isOwner(user)) {
            return true;
        }
        if (isManager(user)) {
            return managerCanAccessCustomer(user, customerId);
        }
        return user != null && user.getRole() == AgentRole.TMK
                && user.hasPermission(com.example.aitmk.security.auth.Permission.CHAT_VIEW_OWN)
                && tmkServiceStatus(user, customerId).isPresent();
    }

    public boolean canReplyCustomer(AuthenticatedUser user, String customerId) {
        if (isOwner(user)) {
            return true;
        }
        if (isManager(user)) {
            return managerCanAccessCustomer(user, customerId);
        }
        if (user == null || !user.hasPermission(com.example.aitmk.security.auth.Permission.CHAT_REPLY_ASSIGNED)) return false;
        return tmkServiceStatus(user, customerId)
                .map("服务中"::equals)
                .orElse(false);
    }

    public boolean canViewAgent(AuthenticatedUser user, String agentRowId) {
        if (!StringUtils.hasText(agentRowId)) {
            return false;
        }
        if (isOwner(user)) {
            return true;
        }
        if (isManager(user)) {
            List<String> managed = user.getManagedAgentIds();
            return (managed != null && managed.contains(agentRowId))
                    || user.getAccountRowId().equals(agentRowId);
        }
        return agentRowId.equals(user.getAccountRowId());
    }

    public boolean canManageAccounts(AuthenticatedUser user) {
        return isOwner(user) && user.hasPermission(com.example.aitmk.security.auth.Permission.AGENT_MANAGE);
    }

    public boolean canManageAgentLevels(AuthenticatedUser user) {
        return isOwner(user) || isManager(user);
    }

    public boolean canJoinConversation(AuthenticatedUser user, String customerId) {
        return (isOwner(user) || isManager(user)) && canViewCustomer(user, customerId);
    }

    public String resolvePermittedAgent(AuthenticatedUser user, String requestedAgentRowId) {
        if (!StringUtils.hasText(requestedAgentRowId) || !canViewAgent(user, requestedAgentRowId.trim())) {
            return user.getAccountRowId();
        }
        return requestedAgentRowId.trim();
    }

    private boolean managerCanAccessCustomer(AuthenticatedUser user, String customerId) {
        List<String> managed = user.getManagedAgentIds();
        if (managed == null || managed.isEmpty()) {
            return false;
        }
        Optional<String> servingAgent = agentDispatchService.getAssignedAgent(customerId);
        if (servingAgent.isEmpty() && legacyCrmFallback) servingAgent = crmOpenApiService.findServingAgentRowId(customerId);
        return servingAgent.map(managed::contains).orElse(false);
    }

    private Optional<String> tmkServiceStatus(AuthenticatedUser user, String customerId) {
        if (user == null || !StringUtils.hasText(user.getAccountRowId()) || !StringUtils.hasText(customerId)) {
            return Optional.empty();
        }
        Optional<String> current = agentDispatchService.getAssignedAgent(customerId)
                .filter(user.getAccountRowId()::equals)
                .map(agent -> "服务中");
        if (current.isPresent()) return current;
        if (legacyCrmFallback) {
            Map<String, String> crmStatuses = crmOpenApiService.listAgentCustomerServiceStatus(user.getAccountRowId());
            if (crmStatuses.containsKey(customerId)) return Optional.ofNullable(crmStatuses.get(customerId));
        }
        return assignmentPersistenceService.hasServed(customerId, user.getAccountRowId()) ? Optional.of("已关闭") : Optional.empty();
    }

    private boolean isOwner(AuthenticatedUser user) {
        return user != null && user.getRole() == AgentRole.OWNER
                && user.hasPermission(com.example.aitmk.security.auth.Permission.CHAT_VIEW_ALL);
    }

    private boolean isManager(AuthenticatedUser user) {
        return user != null && user.getRole() == AgentRole.MANAGER
                && user.hasPermission(com.example.aitmk.security.auth.Permission.CHAT_VIEW_MANAGED);
    }
}
