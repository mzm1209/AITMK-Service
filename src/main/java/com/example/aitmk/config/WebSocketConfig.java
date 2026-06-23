package com.example.aitmk.config;

import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.JwtTokenService;
import com.example.aitmk.security.permission.ChatPermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * WebSocket/STOMP 配置：
 * 客户端订阅 /topic/agent/{agentRowId}，接收分配给该坐席的会话推送。
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtTokenService jwtTokenService;
    private final ChatPermissionService chatPermissionService;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    AuthenticatedUser user = parseUser(accessor);
                    accessor.setUser(toPrincipal(user));
                }
                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    Principal principal = accessor.getUser();
                    if (!(principal instanceof UsernamePasswordAuthenticationToken auth)
                            || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
                        throw new org.springframework.security.access.AccessDeniedException("未登录或 token 失效");
                    }
                    String destination = accessor.getDestination();
                    if (destination != null && destination.startsWith("/topic/agent/")) {
                        String agentRowId = destination.substring("/topic/agent/".length());
                        if (!chatPermissionService.canViewAgent(user, agentRowId)) {
                            throw new org.springframework.security.access.AccessDeniedException("无权订阅该坐席频道");
                        }
                    }
                }
                return message;
            }
        });
    }

    private AuthenticatedUser parseUser(StompHeaderAccessor accessor) {
        String token = firstHeader(accessor, "Authorization");
        if (token == null || token.isBlank()) {
            token = firstHeader(accessor, "authorization");
        }
        if (token == null || token.isBlank()) {
            token = firstHeader(accessor, "token");
        }
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token == null || token.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("未登录或 token 失效");
        }
        return jwtTokenService.parseToken(token);
    }

    private String firstHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private UsernamePasswordAuthenticationToken toPrincipal(AuthenticatedUser user) {
        var authorities = user.getPermissions().stream()
                .map(permission -> new SimpleGrantedAuthority(permission.name()))
                .collect(Collectors.toSet());
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        return new AgentPrincipal(user, authorities);
    }

    private static final class AgentPrincipal extends UsernamePasswordAuthenticationToken {
        private final String name;
        AgentPrincipal(AuthenticatedUser user, java.util.Collection<? extends org.springframework.security.core.GrantedAuthority> authorities) {
            super(user, null, authorities); this.name = user.getAccountRowId();
        }
        @Override public String getName() { return name; }
    }
}
