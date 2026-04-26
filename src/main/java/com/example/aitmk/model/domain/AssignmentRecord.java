package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssignmentRecord {

    private String customerPhone;
    private String agentRowId;
    private String customerNickname;
}
