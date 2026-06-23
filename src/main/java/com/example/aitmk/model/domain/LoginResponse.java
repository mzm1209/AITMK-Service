package com.example.aitmk.model.domain;

import com.example.aitmk.security.auth.Permission;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Set;
import java.util.List;

@Data
@Builder
public class LoginResponse {

    private boolean success;
    private String message;
    /** CRM 账号表记录 rowId */
    private String accountRowId;
    /** 关联用户（人员 ID，逗号分隔） */
    private String relatedUserIds;
    /** 登录时固化到 JWT 的管理范围；CRM 修改后需重新登录才生效。 */
    private List<String> managedAgentIds;
    private String role;
    private Set<Permission> permissions;
    private String accessToken;
    private Instant expiresAt;
}
