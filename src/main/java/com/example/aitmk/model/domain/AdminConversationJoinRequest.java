package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AdminConversationJoinRequest {

    @NotBlank
    private String mode;

    private String remark;
}
