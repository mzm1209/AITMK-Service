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
     * 媒体 ID（优先使用，来自 Meta 上传接口返回）。
     */
    private String mediaId;

    /**
     * 媒体链接（当未提供 mediaId 时使用）。
     */
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
