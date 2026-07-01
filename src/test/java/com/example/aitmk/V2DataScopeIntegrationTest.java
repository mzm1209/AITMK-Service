package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.v2.ConversationCommandService;
import com.example.aitmk.service.v2.ConversationQueryService;
import com.example.aitmk.service.v2.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class V2DataScopeIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationQueryService query;
    @Autowired ConversationCommandService command;
    @Autowired DashboardService dashboard;

    @Test void ownerCanUseAllScope() {
        Fixture f=fixture("agent-any");
        assertThat(query.list(user("owner",AgentRole.OWNER,List.of()),"all",null,f.prefix(),null,null,null,null,null,null,30).items())
                .extracting(item -> item.conversationId()).containsExactly(f.conversation().getId().toString());
    }

    @Test void managerManagedScopeReturnsOnlyManagedAgents() {
        String prefix=prefix(); Fixture first=fixture(prefix,"managed-agent-1"); Fixture second=fixture(prefix,"managed-agent-2"); Fixture outside=fixture(prefix,"outside-agent");
        AuthenticatedUser manager=user("manager",AgentRole.MANAGER,List.of("managed-agent-1","managed-agent-2"));
        var page=query.list(manager,"managed",null,prefix,null,null,null,null,null,null,30);
        assertThat(page.items()).extracting(item->item.assignedAgent().agentId())
                .containsExactlyInAnyOrder("managed-agent-1","managed-agent-2");
        assertThatCode(()->query.detail(first.conversation().getId(),manager)).doesNotThrowAnyException();
        assertThatCode(()->query.detail(second.conversation().getId(),manager)).doesNotThrowAnyException();
        assertForbidden(()->query.detail(outside.conversation().getId(),manager));
    }

    @Test void managerAllScopeAndOutsideAgentAreForbidden() {
        AuthenticatedUser manager=user("manager",AgentRole.MANAGER,List.of("managed-agent"));
        assertForbidden(() -> query.list(manager,"all",null,null,null,null,null,null,null,null,30));
        assertForbidden(() -> query.list(manager,"managed",null,null,null,null,null,null,"outside-agent",null,30));
    }

    @Test void managerCannotOpenConversationOutsideManagedRange() {
        Fixture outside=fixture("outside-agent");
        assertForbidden(() -> query.detail(outside.conversation().getId(),user("manager",AgentRole.MANAGER,List.of("managed-agent"))));
    }

    @Test void emptyManagerScopeReturnsEmptyPage() {
        Fixture f=fixture("managed-agent");
        assertThat(query.list(user("manager",AgentRole.MANAGER,List.of()),"managed",null,f.prefix(),null,null,null,null,null,null,30).items()).isEmpty();
    }

    @Test void tmkMineWorksButManagedAllAndOtherConversationAreForbidden() {
        String prefix=prefix(); Fixture mine=fixture(prefix,"tmk-1"); Fixture other=fixture(prefix,"tmk-2");
        AuthenticatedUser tmk=user("tmk-1",AgentRole.TMK,List.of());
        assertThat(query.list(tmk,"mine",null,prefix,null,null,null,null,null,null,30).items())
                .extracting(item -> item.conversationId()).containsExactly(mine.conversation().getId().toString());
        assertForbidden(() -> query.list(tmk,"managed",null,prefix,null,null,null,null,null,null,30));
        assertForbidden(() -> query.list(tmk,"all",null,prefix,null,null,null,null,null,null,30));
        assertForbidden(() -> query.detail(other.conversation().getId(),tmk));
    }

    @Test void dashboardUsesTheSameScopeRules() {
        assertThatCode(() -> dashboard.summary(user("owner",AgentRole.OWNER,List.of()),"all")).doesNotThrowAnyException();
        assertThat(dashboard.summary(user("manager",AgentRole.MANAGER,List.of()),"managed").activeConversations()).isZero();
        assertForbidden(() -> dashboard.summary(user("manager",AgentRole.MANAGER,List.of("tmk-1")),"all"));
        assertForbidden(() -> dashboard.summary(user("tmk-1",AgentRole.TMK,List.of()),"managed"));
    }

    @Test void tmkCanTransferOwnConversationToAnyTargetRoleOutsideScope() {
        assertTransferAllowed(user("tmk-1",AgentRole.TMK,List.of()), fixture("tmk-1"), "tmk-2");
        assertTransferAllowed(user("tmk-3",AgentRole.TMK,List.of()), fixture("tmk-3"), "manager-1");
        assertTransferAllowed(user("tmk-4",AgentRole.TMK,List.of()), fixture("tmk-4"), "owner-1");
    }

    @Test void managerCanTransferManagedConversationToTargetsOutsideManagedScope() {
        AuthenticatedUser manager=user("manager-1",AgentRole.MANAGER,List.of("managed-tmk"));
        assertTransferAllowed(manager, fixture("managed-tmk"), "outside-tmk");
        assertTransferAllowed(manager, fixture("managed-tmk"), "owner-1");
    }

    @Test void ownerCanTransferConversationToManagerOrTmk() {
        AuthenticatedUser owner=user("owner-1",AgentRole.OWNER,List.of());
        assertTransferAllowed(owner, fixture("manager-1"), "manager-2");
        assertTransferAllowed(owner, fixture("manager-1"), "tmk-1");
    }

    private Fixture fixture(String agent){return fixture(prefix(),agent);}
    private Fixture fixture(String prefix,String agent){
        ResourceEntity r=new ResourceEntity();r.setCustomerPhone(prefix+UUID.randomUUID().toString().replace("-","").substring(0,8));r.setAssignedAgentId(agent);r=resources.saveAndFlush(r);
        ConversationEntity c=new ConversationEntity();c.setResourceId(r.getId());c.setCustomerPhone(r.getCustomerPhone());c.setAssignedAgentId(agent);c=conversations.saveAndFlush(c);
        return new Fixture(prefix,r,c);
    }
    private String prefix(){return "ds"+UUID.randomUUID().toString().replace("-","").substring(0,6);}
    private AuthenticatedUser user(String id,AgentRole role,List<String> managed){return AuthenticatedUser.builder().accountRowId(id).role(role).permissions(Set.copyOf(Permission.defaultsFor(role))).managedAgentIds(managed).build();}
    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(V2Exception.class,e->{assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);assertThat(e.getCode()).isEqualTo("FORBIDDEN");});}
    private void assertTransferAllowed(AuthenticatedUser user, Fixture fixture, String targetAgentId) {
        command.transfer(fixture.conversation().getId(), new V2Api.TransferRequest(targetAgentId, "handoff", fixture.conversation().getVersion()), user);
        assertThat(conversations.findById(fixture.conversation().getId()).orElseThrow().getAssignedAgentId()).isEqualTo(targetAgentId);
        assertThat(query.transferResult(fixture.conversation().getId(), user).assignedAgent().agentId()).isEqualTo(targetAgentId);
    }
    private record Fixture(String prefix,ResourceEntity resource,ConversationEntity conversation){}
}
