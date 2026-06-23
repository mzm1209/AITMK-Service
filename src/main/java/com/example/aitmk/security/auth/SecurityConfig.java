package com.example.aitmk.security.auth;

import com.example.aitmk.model.api.ApiErrorResponse;
import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/webhook", "/webhook/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> writeError(request,response,HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","未登录或 token 失效"))
                        .accessDeniedHandler((request, response, accessDeniedException) -> writeError(request,response,HttpStatus.FORBIDDEN,"FORBIDDEN","无权限访问该资源"))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,
                            HttpStatus status,String code,String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Object body=request.getRequestURI().startsWith("/api/v2/")
                ?new V2Api.Failure(false,new V2Api.Error(code,message,null),RequestIds.current())
                :ApiErrorResponse.of(code,message);
        objectMapper.writeValue(response.getWriter(),body);
    }
}
