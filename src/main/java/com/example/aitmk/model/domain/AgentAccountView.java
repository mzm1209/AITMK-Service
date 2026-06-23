package com.example.aitmk.model.domain;

import java.util.List;

/** 账号管理业务 DTO；不暴露 CRM controlId。 */
public record AgentAccountView(String rowId, String loginAccount, String relatedUserIds, String agentLevel,
                               String role, boolean enabled, List<String> managedAgentIds) {}
