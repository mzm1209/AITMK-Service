package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SessionTransferRequest {
    @NotBlank(message = "客户电话不能为空")
    private String customerPhone;
    @NotBlank(message = "目标坐席不能为空")
    private String targetAgentRowId;
}
