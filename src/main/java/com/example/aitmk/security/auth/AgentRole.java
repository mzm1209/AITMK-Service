package com.example.aitmk.security.auth;

public enum AgentRole {
    OWNER,
    MANAGER,
    TMK;

    public static AgentRole from(String value) {
        if (value == null || value.isBlank()) {
            return TMK;
        }
        try {
            return AgentRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return TMK;
        }
    }
}
