package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AgentStatusUpdateRequest {
    @NotBlank
    private String agentRowId;
    @NotBlank
    private String status;
}
