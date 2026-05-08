package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AutoReplyScriptUpsertRequest {
    @NotBlank(message = "首次回复话术不能为空")
    private String firstReply;
}
