package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 人工发送媒体消息请求参数。
 */
@Data
public class ManualMediaReplyRequest {

    /** 企业发送方号码 ID（Meta Graph API phone number id）。 */
    @NotBlank
    private String from;

    /** 客户 ID（接收方号码/标识）。 */
    @NotBlank
    private String customerId;

    /**
     * 媒体类型：image / video / audio / document
     */
    @NotBlank
    private String mediaType;

    /**
     * 媒体链接（Meta 可访问 URL）。
     */
    @NotBlank
    private String mediaUrl;

    /**
     * 可选说明文案（image/video/document 支持）。
     */
    private String caption;

    /**
     * 文件名（document 可选）。
     */
    private String filename;
}
