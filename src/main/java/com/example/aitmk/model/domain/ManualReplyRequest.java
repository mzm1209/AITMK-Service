package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ManualReplyRequest {

    @NotBlank
    private String from;

    @NotBlank
    private String customerId;

    @NotBlank
    private String message;
}
