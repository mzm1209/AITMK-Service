package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CrmAgentAccount {

    private String rowId;
    private String loginAccount;
    private String relatedUserIds;
}
