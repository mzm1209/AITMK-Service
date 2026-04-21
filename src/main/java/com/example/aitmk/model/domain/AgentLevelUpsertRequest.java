package com.example.aitmk.model.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 坐席等级新增/修改请求。
 */
@Data
public class AgentLevelUpsertRequest {

    @NotBlank
    private String levelName;

    @NotNull
    @DecimalMin("0")
    private Double weight;

    @NotNull
    @DecimalMin("0")
    private Double maxLoad;
}
