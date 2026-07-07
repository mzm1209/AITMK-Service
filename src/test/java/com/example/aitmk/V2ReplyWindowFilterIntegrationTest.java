package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.service.v2.ConversationQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class V2ReplyWindowFilterIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationQueryService query;

    @Test void openReturnsOnlyUnexpiredConversationsWithCustomerMessage() {
        String prefix = prefix();
        Fixture open = fixture(prefix, "agent-window", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", Instant.now().minus(25, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", null);

        assertThat(ids(list("open", prefix))).containsExactly(open.id());
    }

    @Test void expiredReturnsOnlyExpiredConversationsWithCustomerMessage() {
        String prefix = prefix();
        Fixture expired = fixture(prefix, "agent-window", Instant.now().minus(25, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", null);

        assertThat(ids(list("expired", prefix))).containsExactly(expired.id());
    }

    @Test void lt15mReturnsUnexpiredConversationsWithAtMostFifteenMinutesRemaining() {
        String prefix = prefix();
        Fixture lt15m = fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(50, ChronoUnit.MINUTES));
        fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(prefix, "agent-window", Instant.now().minus(25, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", null);

        assertThat(ids(list("lt15m", prefix))).containsExactly(lt15m.id());
    }

    @Test void lt1hIncludesLt15m() {
        String prefix = prefix();
        Fixture lt15m = fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(50, ChronoUnit.MINUTES));
        Fixture lt1h = fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(prefix, "agent-window", Instant.now().minus(22, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", Instant.now().minus(25, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", null);

        assertThat(ids(list("lt1h", prefix))).containsExactlyInAnyOrder(lt15m.id(), lt1h.id());
    }

    @Test void lt4hIncludesLt1hAndLt15m() {
        String prefix = prefix();
        Fixture lt15m = fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(50, ChronoUnit.MINUTES));
        Fixture lt1h = fixture(prefix, "agent-window", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        Fixture lt4h = fixture(prefix, "agent-window", Instant.now().minus(22, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", Instant.now().minus(19, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", Instant.now().minus(25, ChronoUnit.HOURS));
        fixture(prefix, "agent-window", null);

        assertThat(ids(list("lt4h", prefix))).containsExactlyInAnyOrder(lt15m.id(), lt1h.id(), lt4h.id());
    }

    @Test void replyWindowCombinesWithScopeAssignedAgentAndKeyword() {
        String prefix = prefix();
        String keyword = prefix + "match";
        Fixture target = fixture(keyword, "agent-a", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix + "other", "agent-a", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(keyword, "agent-b", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(keyword, "agent-a", Instant.now().minus(25, ChronoUnit.HOURS));

        var page = query.list(manager("manager", List.of("agent-a", "agent-b")), "managed", null,
                keyword, null, null, null, null, "agent-a", "open", null, 30);

        assertThat(ids(page)).containsExactly(target.id());
    }

    private V2Api.CursorPage<V2Api.ConversationSummary> list(String replyWindow, String keyword) {
        return query.list(owner("owner-window"), "all", null, keyword, null, null, null,
                null, null, replyWindow, null, 30);
    }

    private List<String> ids(V2Api.CursorPage<V2Api.ConversationSummary> page) {
        return page.items().stream().map(V2Api.ConversationSummary::conversationId).toList();
    }

    private Fixture fixture(String phonePrefix, String agent, Instant lastCustomerMessageAt) {
        String phone = phonePrefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone(phone);
        resource.setAssignedAgentId(agent);
        resource.setLastCustomerMessageAt(lastCustomerMessageAt);
        resource = resources.saveAndFlush(resource);

        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(phone);
        conversation.setAssignedAgentId(agent);
        conversation = conversations.saveAndFlush(conversation);
        return new Fixture(conversation.getId().toString());
    }

    private String prefix() {
        return "rw" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private AuthenticatedUser owner(String id) {
        return user(id, AgentRole.OWNER, List.of());
    }

    private AuthenticatedUser manager(String id, List<String> managed) {
        return user(id, AgentRole.MANAGER, managed);
    }

    private AuthenticatedUser user(String id, AgentRole role, List<String> managed) {
        return AuthenticatedUser.builder()
                .accountRowId(id)
                .role(role)
                .permissions(Set.copyOf(Permission.defaultsFor(role)))
                .managedAgentIds(managed)
                .build();
    }

    private record Fixture(String id) {}
}
