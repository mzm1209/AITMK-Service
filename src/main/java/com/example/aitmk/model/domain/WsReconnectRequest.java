package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WsReconnectRequest {

    @NotBlank
    private String agentRowId;
}
