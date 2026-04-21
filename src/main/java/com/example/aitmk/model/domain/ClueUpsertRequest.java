package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 线索管理新增/修改请求：controls 由前端按线索表字段定义传入。
 */
@Data
public class ClueUpsertRequest {

    @NotEmpty(message = "controls 不能为空")
    private List<Map<String, Object>> controls;

    /** 是否触发 CRM 工作流，默认 true。 */
    private Boolean triggerWorkflow = Boolean.TRUE;
}
