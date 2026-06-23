package com.example.aitmk.security.auth;

import com.example.aitmk.model.domain.CrmAgentAccount;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Date;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    @Test
    void generateAndParseToken() {
        JwtTokenService tokenService = new JwtTokenService("test-secret-test-secret-test-secret-123", 3600);
        CrmAgentAccount account = CrmAgentAccount.builder()
                .rowId("agent-1")
                .loginAccount("tmk01")
                .relatedUserIds("user-1")
                .role(AgentRole.MANAGER)
                .managedAgentIds(List.of("agent-2"))
                .enabled(true)
                .build();

        AuthenticatedUser user = tokenService.parseToken(tokenService.generateToken(account));

        assertThat(user.getAccountRowId()).isEqualTo("agent-1");
        assertThat(user.getLoginAccount()).isEqualTo("tmk01");
        assertThat(user.getRole()).isEqualTo(AgentRole.MANAGER);
        assertThat(user.getManagedAgentIds()).containsExactly("agent-2");
        assertThat(user.getPermissions()).contains(Permission.CHAT_JOIN_ANY, Permission.CHAT_REPLY_ASSIGNED);
    }

    @Test
    void missingManagedAgentClaimIsEmptyAndDoesNotGrantAllAccess() {
        String secret = "test-secret-test-secret-test-secret-123";
        JwtTokenService tokenService = new JwtTokenService(secret, 3600);
        Instant now = Instant.now();
        String legacyToken = Jwts.builder().subject("manager-legacy").claim("role", "MANAGER")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();

        AuthenticatedUser user = tokenService.parseToken(legacyToken);

        assertThat(user.getManagedAgentIds()).isEmpty();
        assertThat(user.getPermissions()).contains(Permission.CHAT_VIEW_MANAGED).doesNotContain(Permission.CHAT_VIEW_ALL);
    }
}
