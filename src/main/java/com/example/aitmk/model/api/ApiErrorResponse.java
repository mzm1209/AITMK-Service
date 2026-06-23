package com.example.aitmk.model.api;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiErrorResponse {

    private boolean success;
    private String code;
    private String message;

    public static ApiErrorResponse of(String code, String message) {
        return ApiErrorResponse.builder()
                .success(false)
                .code(code)
                .message(message)
                .build();
    }
}
