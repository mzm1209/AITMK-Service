package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 坐席账号新增/修改参数。
 */
@Data
public class AgentAccountUpsertRequest {

    @NotBlank
    private String loginAccount;

    private String password;

    /** 人员ID，多个逗号分隔。 */
    private String relatedUserIds;
}
