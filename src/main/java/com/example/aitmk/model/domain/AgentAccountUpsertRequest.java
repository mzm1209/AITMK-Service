package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonAlias;
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

    /**
     * 坐席等级（关联记录 dataType=29），多条 rowId 用逗号分隔，全量覆盖。
     */
    @JsonAlias({"agentLevelRowIds"})
    private String agentLevel;
}
