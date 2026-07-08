package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.LeadRecordEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.LeadRecordRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.security.auth.AgentRole;
import com.example.aitmk.security.auth.AuthenticatedUser;
import com.example.aitmk.security.auth.Permission;
import com.example.aitmk.service.WorksheetFieldService;
import com.example.aitmk.service.v2.ConversationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class V2CrmConversationFilterIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired LeadRecordRepository leadRecords;
    @Autowired ConversationQueryService query;
    @MockBean WorksheetFieldService worksheetFields;

    @BeforeEach void setUpWorksheetFields() {
        when(worksheetFields.getFields("leads_bank")).thenReturn(new V2Api.WorksheetFieldsView("leads_bank", "线索管理", List.of()));
    }

    @Test void leadTypeFiltersLocalLeadRecordsOnly() {
        String prefix = prefix();
        Fixture target = fixture(prefix, "agent-crm", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Renewal", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixtureWithoutLead(prefix, "agent-crm", Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(ids(list(prefix, "Trial", null))).containsExactly(target.id());
    }

    @Test void leadStatusFiltersLocalLeadRecordsOnly() {
        String prefix = prefix();
        Fixture target = fixture(prefix, "agent-crm", "Trial", "Paid", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixtureWithoutLead(prefix, "agent-crm", Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(ids(list(prefix, null, "Paid"))).containsExactly(target.id());
    }

    @Test void leadTypeAndLeadStatusCombineWithAndSemantics() {
        String prefix = prefix();
        Fixture target = fixture(prefix, "agent-crm", "Trial", "Paid", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Renewal", "Paid", Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(ids(list(prefix, "Trial", "Paid"))).containsExactly(target.id());
    }

    @Test void conversationWithoutLeadRecordDoesNotMatchCrmFilter() {
        String prefix = prefix();
        fixtureWithoutLead(prefix, "agent-crm", Instant.now().minus(2, ChronoUnit.HOURS));

        assertThat(ids(list(prefix, "Trial", null))).isEmpty();
    }

    @Test void crmFiltersCombineWithReplyWindowAssignedAgentAndKeyword() {
        String prefix = prefix();
        String keyword = prefix + "match";
        Fixture target = fixture(keyword, "agent-a", "Trial", "Open", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(prefix + "other", "agent-a", "Trial", "Open", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(keyword, "agent-b", "Trial", "Open", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(keyword, "agent-a", "Renewal", "Open", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(keyword, "agent-a", "Trial", "Closed", Instant.now().minus(23, ChronoUnit.HOURS).minus(30, ChronoUnit.MINUTES));
        fixture(keyword, "agent-a", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));

        var page = query.list(manager("manager", List.of("agent-a", "agent-b")), "managed", null,
                keyword, null, null, null, null, "agent-a", "lt1h", "Trial", "Open", null, 30);

        assertThat(ids(page)).containsExactly(target.id());
    }

    @Test void filterOptionsReturnDistinctLocalLeadValues() {
        String prefix = prefix();
        fixture(prefix, "agent-crm", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Trial", "Open", Instant.now().minus(2, ChronoUnit.HOURS));
        fixture(prefix, "agent-crm", "Renewal", "Paid", Instant.now().minus(2, ChronoUnit.HOURS));
        lead(prefix + "blank-type", "", "   ");
        lead(prefix + "null-type", null, null);

        var options = query.filterOptions();

        assertThat(options.leadTypes()).extracting(V2Api.ConversationFilterOption::value)
                .contains("Renewal", "Trial")
                .doesNotContain("", "   ");
        assertThat(options.leadStatuses()).extracting(V2Api.ConversationFilterOption::value)
                .contains("Open", "Paid")
                .doesNotContain("", "   ");
        assertThat(options.leadTypes()).allSatisfy(option -> assertThat(option.label()).isEqualTo(option.value()));
        assertThat(options.leadStatuses()).allSatisfy(option -> assertThat(option.label()).isEqualTo(option.value()));
    }

    @Test void filterOptionsMergeWorksheetEnumsWithLocalValues() {
        when(worksheetFields.getFields("leads_bank")).thenReturn(new V2Api.WorksheetFieldsView("leads_bank", "线索管理", List.of(
                new V2Api.FieldConfigView("681c86c01e19a610d7200418", "线索类型", 11, List.of(
                        new V2Api.FieldOption("type-a", "Type A"),
                        new V2Api.FieldOption("type-b", "Type B"))),
                new V2Api.FieldConfigView("66b5e34a7e23d13674f24129", "线索状态", 11, List.of(
                        new V2Api.FieldOption("open", "Open"),
                        new V2Api.FieldOption("closed", "Closed")))
        )));
        String prefix = prefix();
        fixture(prefix, "agent-crm", "Local Only Type", "Local Only Status", Instant.now().minus(2, ChronoUnit.HOURS));

        var options = query.filterOptions();

        assertThat(options.leadTypes()).extracting(V2Api.ConversationFilterOption::value)
                .containsExactly("Type A", "Type B", "Local Only Type");
        assertThat(options.leadStatuses()).extracting(V2Api.ConversationFilterOption::value)
                .containsExactly("Open", "Closed", "Local Only Status");
    }

    @Test void filterOptionsReturnEmptyArraysWhenNoLocalLeadValues() {
        var options = query.filterOptions();

        assertThat(options.leadTypes()).isEmpty();
        assertThat(options.leadStatuses()).isEmpty();
    }

    private V2Api.CursorPage<V2Api.ConversationSummary> list(String keyword, String leadType, String leadStatus) {
        return query.list(owner("owner-crm"), "all", null, keyword, null, null, null,
                null, null, null, leadType, leadStatus, null, 30);
    }

    private List<String> ids(V2Api.CursorPage<V2Api.ConversationSummary> page) {
        return page.items().stream().map(V2Api.ConversationSummary::conversationId).toList();
    }

    private Fixture fixture(String phonePrefix, String agent, String leadType, String leadStatus, Instant lastCustomerMessageAt) {
        Fixture fixture = fixtureWithoutLead(phonePrefix, agent, lastCustomerMessageAt);
        LeadRecordEntity lead = new LeadRecordEntity();
        lead.setCustomerPhone(fixture.phone());
        lead.setCrmRowId("crm-" + fixture.phone());
        lead.setLeadsType(leadType);
        lead.setLeadsStatus(leadStatus);
        lead.setLeadData("{\"leadsType\":\"" + leadType + "\",\"leadsStatus\":\"" + leadStatus + "\"}");
        leadRecords.saveAndFlush(lead);
        return fixture;
    }

    private void lead(String phone, String leadType, String leadStatus) {
        LeadRecordEntity lead = new LeadRecordEntity();
        lead.setCustomerPhone(phone);
        lead.setCrmRowId("crm-" + phone);
        lead.setLeadsType(leadType);
        lead.setLeadsStatus(leadStatus);
        leadRecords.saveAndFlush(lead);
    }

    private Fixture fixtureWithoutLead(String phonePrefix, String agent, Instant lastCustomerMessageAt) {
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
        return new Fixture(phone, conversation.getId().toString());
    }

    private String prefix() {
        return "cf" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
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

    private record Fixture(String phone, String id) {}
}
