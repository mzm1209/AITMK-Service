package com.example.aitmk.service.v2;

import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.security.auth.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class V2AccessService {
    public enum DataScope { MINE, MANAGED, ALL }

    public DataScope requireScope(AuthenticatedUser user, String rawScope) {
        String value = rawScope == null || rawScope.isBlank() ? "mine" : rawScope.trim().toLowerCase(Locale.ROOT);
        DataScope scope;
        try {
            scope = DataScope.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new V2Exception(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "scope 仅支持 mine、managed、all");
        }
        boolean allowed = switch (scope) {
            case MINE -> hasOwnAccess(user);
            case MANAGED -> user != null && user.getRole() == AgentRole.MANAGER
                    && user.hasPermission(Permission.CHAT_VIEW_MANAGED);
            case ALL -> user != null && user.getRole() == AgentRole.OWNER
                    && user.hasPermission(Permission.CHAT_VIEW_ALL);
        };
        if (!allowed) throw forbidden("无权使用 scope=" + value);
        return scope;
    }

    public List<String> agentsForScope(AuthenticatedUser user, DataScope scope) {
        return switch (scope) {
            case MINE -> List.of(user.getAccountRowId());
            case MANAGED -> { var ids = new java.util.ArrayList<>(user.getManagedAgentIds() == null ? List.of() : user.getManagedAgentIds()); ids.add(user.getAccountRowId()); yield ids; }
            case ALL -> null;
        };
    }

    public void requireAssignedAgent(AuthenticatedUser user, String assignedAgentId) {
        if (assignedAgentId == null || assignedAgentId.isBlank()) return;
        String id = assignedAgentId.trim();
        if (user != null && user.getRole() == AgentRole.OWNER && user.hasPermission(Permission.CHAT_VIEW_ALL)) return;
        if (user != null && user.getRole() == AgentRole.MANAGER && user.hasPermission(Permission.CHAT_VIEW_MANAGED)
                && user.getManagedAgentIds() != null && user.getManagedAgentIds().contains(id)) return;
        if (user != null && user.getRole() == AgentRole.MANAGER && user.hasPermission(Permission.CHAT_VIEW_MANAGED) && id.equals(user.getAccountRowId())) return;
        if (user != null && user.getRole() == AgentRole.TMK && user.hasPermission(Permission.CHAT_VIEW_OWN)
                && id.equals(user.getAccountRowId())) return;
        throw forbidden("无权查询指定坐席的数据");
    }

    public void requireAgentWithinScope(AuthenticatedUser user, DataScope scope, String assignedAgentId) {
        requireAssignedAgent(user, assignedAgentId);
        if (assignedAgentId == null || assignedAgentId.isBlank() || scope == DataScope.ALL) return;
        if (!agentsForScope(user, scope).contains(assignedAgentId.trim())) {
            throw forbidden("指定坐席不属于当前 scope");
        }
    }

    public boolean canView(AuthenticatedUser user, ConversationEntity conversation) {
        if (user == null || conversation == null) return false;
        if (user.getRole() == AgentRole.OWNER) return user.hasPermission(Permission.CHAT_VIEW_ALL);
        if (user.getRole() == AgentRole.MANAGER) {
            return user.hasPermission(Permission.CHAT_VIEW_MANAGED)
                    && (user.getAccountRowId().equals(conversation.getAssignedAgentId())
                        || (conversation.getAssignedAgentId() != null && user.getManagedAgentIds() != null
                            && user.getManagedAgentIds().contains(conversation.getAssignedAgentId())));
        }
        return user.getRole() == AgentRole.TMK && user.hasPermission(Permission.CHAT_VIEW_OWN)
                && user.getAccountRowId().equals(conversation.getAssignedAgentId());
    }

    public void requireView(AuthenticatedUser user, ConversationEntity conversation) {
        if (!canView(user, conversation)) throw forbidden("无权访问该会话");
    }

    public void require(AuthenticatedUser user, Permission permission) {
        if (user == null || !user.hasPermission(permission)) throw forbidden("缺少权限 " + permission.name());
    }

    public void requireReply(AuthenticatedUser user, ConversationEntity conversation) {
        requireView(user, conversation);
        require(user, Permission.CHAT_REPLY_ASSIGNED);
        if (!canReply(user, conversation.getAssignedAgentId())) {
            throw new V2Exception(HttpStatus.FORBIDDEN, "NOT_CURRENT_ASSIGNEE", "仅当前负责人可回复");
        }
    }

    /** Whether the user can reply to a conversation assigned to the given agent. */
    public boolean canReply(AuthenticatedUser user, String assignedAgentId) {
        if (user == null || assignedAgentId == null) return false;
        if (user.getRole() == AgentRole.OWNER) return user.hasPermission(Permission.CHAT_REPLY_ASSIGNED);
        if (user.getRole() == AgentRole.MANAGER) {
            return user.hasPermission(Permission.CHAT_REPLY_ASSIGNED)
                && (user.getAccountRowId().equals(assignedAgentId)
                    || (user.getManagedAgentIds() != null
                        && user.getManagedAgentIds().contains(assignedAgentId)));
        }
        return user.hasPermission(Permission.CHAT_REPLY_ASSIGNED)
            && user.getAccountRowId().equals(assignedAgentId);
    }

    private boolean hasOwnAccess(AuthenticatedUser user) {
        if (user == null) return false;
        return switch (user.getRole()) {
            case OWNER -> user.hasPermission(Permission.CHAT_VIEW_ALL);
            case MANAGER -> user.hasPermission(Permission.CHAT_VIEW_MANAGED);
            case TMK -> user.hasPermission(Permission.CHAT_VIEW_OWN);
        };
    }

    private V2Exception forbidden(String message) {
        return new V2Exception(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
