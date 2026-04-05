package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 坐席客户视图：用于前端区分可回复与只读历史。
 */
@Data
@Builder
public class AgentCustomerView {

    private String customerId;
    /** 客户昵称（来自 webhook contacts.profile.name）。 */
    private String customerNickname;
    private String lastMessage;
    private Instant lastMessageAt;
    /** 分配服务状态：服务中 / 已关闭 */
    private String serviceStatus;
    /** 是否允许坐席回复 */
    private boolean canReply;
}
