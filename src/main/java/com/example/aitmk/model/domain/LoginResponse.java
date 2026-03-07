package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {

    private boolean success;
    private String message;
    /** CRM 账号表记录 rowId */
    private String accountRowId;
    /** 关联用户（人员 ID，逗号分隔） */
    private String relatedUserIds;
}
