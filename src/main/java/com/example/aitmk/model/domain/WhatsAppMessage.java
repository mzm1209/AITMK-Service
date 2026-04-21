package com.example.aitmk.model.domain;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WhatsAppMessage {

    private String from;
    private String type;
    private String text;
    private String mediaId;
    private String mediaUrl;
    private Double latitude;
    private Double longitude;

    /** 来自 context 的上文消息ID。 */
    private String contextMessageId;
    private String contextFrom;
    private Boolean forwarded;
    private Boolean frequentlyForwarded;

    /** Click to WhatsApp 广告来源字段。 */
    private String referralSourceType;
    private String referralSourceId;
    private String referralHeadline;
    private String referralBody;
    private String referralCtaClid;
}
