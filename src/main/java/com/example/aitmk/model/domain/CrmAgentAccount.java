package com.example.aitmk.model.domain;

import com.example.aitmk.security.auth.AgentRole;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CrmAgentAccount {

    private String rowId;
    private String loginAccount;
    private String relatedUserIds;
    @Builder.Default
    private AgentRole role = AgentRole.TMK;
    @Builder.Default
    private List<String> managedAgentIds = List.of();
    @Builder.Default
    private boolean enabled = true;
}
