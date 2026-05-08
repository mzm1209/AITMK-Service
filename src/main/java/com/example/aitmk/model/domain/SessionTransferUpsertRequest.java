package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SessionTransferUpsertRequest {
    @NotBlank(message = "客户电话不能为空")
    private String customerPhone;
    @NotBlank(message = "原账号不能为空")
    private String fromAgentRowId;
    @NotBlank(message = "转移后账号不能为空")
    private String toAgentRowId;
    private String transferTime;
}
