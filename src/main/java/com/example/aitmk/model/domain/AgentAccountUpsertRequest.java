package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import java.util.List;

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

    /** 业务角色：OWNER / MANAGER / TMK。 */
    private String role;

    /** MANAGER 管理的 CRM 账号 rowId；每次保存均全量覆盖。 */
    private List<String> managedAgentIds = List.of();

    /** 账号状态，支持传 启用/停用。 */
    @JsonAlias({"accountStatus", "status"})
    private String enabled;
}
