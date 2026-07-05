package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Api.DashboardAnalytics;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.AgentAccountCacheService;
import com.example.aitmk.service.v2.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("test")
@Transactional
class DashboardAnalyticsIntegrationTest {
    @Autowired DashboardService dashboard;
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired AssignmentRecordRepository assignments;
    @Autowired ChatMessageRepository messages;
    @Autowired LeadRecordRepository leads;
    @Autowired AgentAccountCacheService agentAccounts;

    @Test
    void managedAnalyticsAggregatesLeadsResponsesActiveAndCrmResolvedStatus() {
        String prefix = prefix();
        String manager = prefix + "-manager";
        String agent = prefix + "-agent";
        agentAccounts.upsert(manager, "Manager " + prefix, AgentRole.MANAGER, List.of(agent));
        agentAccounts.upsert(agent, "TMK " + prefix, AgentRole.TMK, List.of());

        Fixture fixture = fixture(prefix + "-phone-1", agent, Instant.parse("2026-07-02T02:00:00Z"));
        assignment(fixture, agent, Instant.parse("2026-07-02T02:00:00Z"));
        message(fixture, PersistenceEnums.SenderType.CUSTOMER, Instant.parse("2026-07-02T02:00:00Z"));
        message(fixture, PersistenceEnums.SenderType.AGENT, Instant.parse("2026-07-02T02:01:00Z"));
        message(fixture, PersistenceEnums.SenderType.CUSTOMER, Instant.parse("2026-07-02T03:00:00Z"));
        message(fixture, PersistenceEnums.SenderType.AGENT, Instant.parse("2026-07-02T03:03:00Z"));
        lead(fixture.resource().getCustomerPhone(), "Paid");

        DashboardAnalytics result = dashboard.analytics(
                user(manager, AgentRole.MANAGER, List.of(agent)),
                "managed", "day", "2026-07-01", "2026-07-31", null);

        assertThat(result.cards().leadCount()).isEqualTo(1);
        assertThat(result.cards().firstResponseAvgSeconds()).isEqualTo(60);
        assertThat(result.cards().firstResponseP50Seconds()).isEqualTo(60);
        assertThat(result.cards().averageResponseSeconds()).isEqualTo(120);
        assertThat(result.cards().averageResponseP90Seconds()).isEqualTo(180);
        assertThat(result.cards().activeConversations()).isEqualTo(1);
        assertThat(result.cards().resolvedConversations()).isEqualTo(1);
        assertThat(result.leadTrend()).filteredOn(p -> p.bucket().equals("2026-07-02"))
                .singleElement().extracting("leadCount").isEqualTo(1L);
        assertThat(result.agentStats()).anySatisfy(stats -> {
            assertThat(stats.agentId()).isEqualTo(agent);
            assertThat(stats.agentName()).isEqualTo("TMK " + prefix);
            assertThat(stats.leadCount()).isEqualTo(1);
            assertThat(stats.resolvedConversations()).isEqualTo(1);
        });
        assertThat(result.agentStats()).anySatisfy(stats -> {
            assertThat(stats.agentId()).isEqualTo(manager);
            assertThat(stats.firstResponseAvgSeconds()).isNull();
            assertThat(stats.averageResponseSeconds()).isNull();
        });
    }

    @Test
    void optionalAgentIdMustBeInsideBackendComputedScope() {
        String manager = prefix() + "-manager";
        AuthenticatedUser user = user(manager, AgentRole.MANAGER, List.of("visible-agent"));

        assertThatThrownBy(() -> dashboard.analytics(user, "managed", "day",
                "2026-07-01", "2026-07-31", "outside-agent"))
                .isInstanceOfSatisfying(V2Exception.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo("FORBIDDEN");
                });
    }

    private Fixture fixture(String phone, String agent, Instant firstCustomerAt) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone(phone);
        resource.setAssignedAgentId(agent);
        resource.setResourceStatus(PersistenceEnums.ResourceStatus.ASSIGNED);
        resource = resources.saveAndFlush(resource);

        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(phone);
        conversation.setAssignedAgentId(agent);
        conversation.setStatus(PersistenceEnums.ConversationStatus.HUMAN_ACTIVE);
        conversation.setFirstCustomerMessageAt(firstCustomerAt);
        conversation.setFirstAgentReplyAt(firstCustomerAt.plusSeconds(60));
        conversation = conversations.saveAndFlush(conversation);
        return new Fixture(resource, conversation);
    }

    private void assignment(Fixture fixture, String agent, Instant at) {
        AssignmentRecordEntity assignment = new AssignmentRecordEntity();
        assignment.setResourceId(fixture.resource().getId());
        assignment.setConversationId(fixture.conversation().getId());
        assignment.setCustomerPhone(fixture.resource().getCustomerPhone());
        assignment.setAgentId(agent);
        assignment.setAssignedBy("system");
        assignment.setAssignType(PersistenceEnums.AssignType.AUTO);
        assignment.setStatus(PersistenceEnums.AssignmentStatus.SERVING);
        assignment.setAssignedAt(at);
        assignments.saveAndFlush(assignment);
    }

    private void message(Fixture fixture, PersistenceEnums.SenderType sender, Instant at) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setResourceId(fixture.resource().getId());
        message.setConversationId(fixture.conversation().getId());
        message.setCustomerPhone(fixture.resource().getCustomerPhone());
        message.setSenderType(sender);
        message.setSenderId(sender == PersistenceEnums.SenderType.CUSTOMER ? null : fixture.conversation().getAssignedAgentId());
        message.setMessageType(PersistenceEnums.MessageType.TEXT);
        message.setSentStatus(sender == PersistenceEnums.SenderType.CUSTOMER
                ? PersistenceEnums.SentStatus.DELIVERED : PersistenceEnums.SentStatus.SENT);
        message.setContent("hello");
        message.setCreatedAt(at);
        messages.saveAndFlush(message);
    }

    private void lead(String phone, String status) {
        LeadRecordEntity lead = new LeadRecordEntity();
        lead.setCustomerPhone(phone);
        lead.setCrmRowId("crm-" + phone);
        lead.setLeadData("{\"leadsStatus\":\"" + status + "\"}");
        leads.saveAndFlush(lead);
    }

    private AuthenticatedUser user(String id, AgentRole role, List<String> managed) {
        return AuthenticatedUser.builder()
                .accountRowId(id)
                .role(role)
                .permissions(Set.copyOf(Permission.defaultsFor(role)))
                .managedAgentIds(managed)
                .build();
    }

    private String prefix() {
        return "da" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private record Fixture(ResourceEntity resource, ConversationEntity conversation) {}
}
