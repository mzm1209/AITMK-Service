package com.example.aitmk.security.auth;

import com.example.aitmk.model.domain.CrmAgentAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long ttlSeconds;

    public JwtTokenService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.ttl-seconds:86400}") long ttlSeconds) {
        if (secret == null || secret.trim().length() < 32) {
            throw new IllegalStateException("SECURITY_JWT_SECRET is required and must contain at least 32 characters");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.trim().getBytes(StandardCharsets.UTF_8));
        this.ttlSeconds = ttlSeconds;
    }

    public String generateToken(CrmAgentAccount account) {
        AgentRole role = account.getRole() == null ? AgentRole.TMK : account.getRole();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .subject(account.getRowId())
                .claim("username", account.getLoginAccount())
                .claim("role", role.name())
                .claim("relatedUserIds", account.getRelatedUserIds())
                // CRM 管理范围在登录时固化；CRM 变更后旧 JWT 保持旧范围，必须重新登录。
                .claim("managedAgentIds", normalizeIds(account.getManagedAgentIds()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    public AuthenticatedUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        AgentRole role = AgentRole.from(claims.get("role", String.class));
        return AuthenticatedUser.builder()
                .accountRowId(claims.getSubject())
                .loginAccount(claims.get("username", String.class))
                .role(role)
                .permissions(Set.copyOf(Permission.defaultsFor(role)))
                .managedAgentIds(readStringList(claims.get("managedAgentIds")))
                .relatedUserIds(claims.get("relatedUserIds", String.class))
                .build();
    }

    public Instant expiresAt() {
        return Instant.now().plusSeconds(ttlSeconds);
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(item -> item.toString().trim())
                .distinct()
                .toList();
    }

    private List<String> normalizeIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(java.util.Objects::nonNull).map(String::trim)
                .filter(id -> !id.isBlank()).distinct().toList();
    }

}
