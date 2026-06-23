package com.example.aitmk.security.auth;

import java.util.EnumSet;
import java.util.Set;

public enum Permission {
    CHAT_VIEW_OWN,
    CHAT_VIEW_MANAGED,
    CHAT_VIEW_ALL,
    CHAT_VIEW_ASSIGNED,
    CHAT_JOIN_ANY,
    CHAT_REPLY_ASSIGNED,
    RESOURCE_ASSIGN,
    RESOURCE_VIEW_ALL,
    AGENT_MANAGE,
    STATS_VIEW_ALL,
    BROADCAST_MANAGE;

    public static Set<Permission> defaultsFor(AgentRole role) {
        return switch (role) {
            case OWNER -> EnumSet.allOf(Permission.class);
            case MANAGER -> EnumSet.of(
                    CHAT_VIEW_MANAGED,
                    CHAT_VIEW_ASSIGNED,
                    CHAT_JOIN_ANY,
                    CHAT_REPLY_ASSIGNED,
                    RESOURCE_ASSIGN,
                    STATS_VIEW_ALL
            );
            case TMK -> EnumSet.of(CHAT_VIEW_OWN, CHAT_VIEW_ASSIGNED, CHAT_REPLY_ASSIGNED);
        };
    }
}
