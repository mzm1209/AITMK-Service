package com.example.aitmk.model.domain;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    /** 登录账号 */
    @NotBlank
    private String username;

    /** 登录密码 */
    @NotBlank
    private String password;
}
