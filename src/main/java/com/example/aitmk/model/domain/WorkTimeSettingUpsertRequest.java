package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工作时间设置新增/修改请求。
 */
@Data
public class WorkTimeSettingUpsertRequest {

    @NotBlank(message = "工作开始时间不能为空")
    private String startTime;

    @NotBlank(message = "工作结束时间不能为空")
    private String endTime;
}
