package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 人工回复请求参数。
 */
@Data
public class ManualReplyRequest {

    /** 企业发送方号码 ID（Meta Graph API phone number id）。 */
    @NotBlank
    private String from;

    /** 客户 ID（接收方号码/标识）。 */
    @NotBlank
    private String customerId;

    /** 人工回复内容。 */
    @NotBlank
    private String message;
}
