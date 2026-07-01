package com.example.aitmk.service;

import com.example.aitmk.model.entity.AgentAccountEntity;
import com.example.aitmk.repository.AgentAccountRepository;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.support.CrmRelationIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地坐席账号缓存，登录时从 CRM 同步写入，逐步与 CRM 解耦。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAccountCacheService {

    private final AgentAccountRepository repo;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Transactional
    public void upsert(String rowId, String loginAccount) {
        upsert(rowId, loginAccount, AgentRole.TMK, List.of());
    }

    @Transactional
    public void upsert(String rowId, String loginAccount, AgentRole role, List<String> managedAgentIds) {
        if (rowId == null || rowId.isBlank() || loginAccount == null) return;
        AgentAccountEntity entity = repo.findById(rowId).orElseGet(() -> {
            var e = new AgentAccountEntity();
            e.setRowId(rowId);
            return e;
        });
        entity.setLoginAccount(loginAccount);
        AgentRole effectiveRole = role == null ? AgentRole.TMK : role;
        entity.setRole(effectiveRole.name());
        entity.setManagedAgentIds(effectiveRole == AgentRole.MANAGER ? CrmRelationIds.serialize(managedAgentIds) : "");
        entity.setUpdatedAt(Instant.now());
        repo.save(entity);
        cache.put(rowId, loginAccount);
    }

    public Optional<AuthenticatedUser> getUser(String rowId) {
        if (rowId == null || rowId.isBlank()) return Optional.empty();
        return repo.findById(rowId.trim()).map(entity -> {
            AgentRole role = AgentRole.from(entity.getRole());
            return AuthenticatedUser.builder()
                    .accountRowId(entity.getRowId())
                    .loginAccount(entity.getLoginAccount())
                    .role(role)
                    .permissions(Set.copyOf(Permission.defaultsFor(role)))
                    .managedAgentIds(role == AgentRole.MANAGER ? CrmRelationIds.parseText(entity.getManagedAgentIds()) : List.of())
                    .build();
        });
    }

    public String getName(String rowId) {
        if (rowId == null || rowId.isBlank()) return null;
        return cache.computeIfAbsent(rowId, id ->
                repo.findById(id).map(AgentAccountEntity::getLoginAccount).orElse(null));
    }

    /** Batch resolve. Missing keys are loaded from DB (once per key). */
    public Map<String, String> getNames(Collection<String> rowIds) {
        if (rowIds == null || rowIds.isEmpty()) return Map.of();
        Map<String, String> result = new HashMap<>();
        List<String> misses = new ArrayList<>();
        for (String id : rowIds) {
            if (id == null || id.isBlank()) continue;
            String name = cache.get(id);
            if (name != null) result.put(id, name);
            else misses.add(id);
        }
        if (!misses.isEmpty()) {
            List<AgentAccountEntity> entities = repo.findAllById(misses);
            for (AgentAccountEntity e : entities) {
                cache.put(e.getRowId(), e.getLoginAccount());
                result.put(e.getRowId(), e.getLoginAccount());
            }
        }
        return result;
    }
}
