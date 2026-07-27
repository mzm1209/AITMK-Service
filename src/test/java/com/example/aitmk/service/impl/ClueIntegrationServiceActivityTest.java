package com.example.aitmk.service.impl;

import com.example.aitmk.repository.LeadRecordRepository;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.service.CrmOpenApiService;
import com.example.aitmk.model.entity.ChatMessageEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClueIntegrationServiceActivityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsActivityCodeFromReferralText() {
        Optional<String> code = ClueIntegrationService.extractActivityCode(
                null,
                "Halo, dengan wondermind bisa dibantu 😊 [METADULEA220626VB-AM]",
                "ignored");

        assertThat(code).contains("METADULEA220626VB-AM");
    }

    @Test
    void resolvesActivityRowIdByContentName() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadRecordRepository repo = mock(LeadRecordRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ClueIntegrationService service = new ClueIntegrationService(crm, repo, messages, objectMapper);

        when(crm.frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(), eq(1), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"rows":[{"rowid":"nrgl-row-1"}]}}
                        """));

        Optional<String> rowId = service.resolveActivityRowIdFromAdContext(
                "Halo [METADULEA220626VB-AM]");

        assertThat(rowId).contains("nrgl-row-1");

        ArgumentCaptor<List<Map<String, Object>>> filtersCaptor = ArgumentCaptor.forClass(List.class);
        verify(crm).frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), filtersCaptor.capture(),
                eq(1), eq(1), eq(0), anyList());
        assertThat(filtersCaptor.getValue()).singleElement().satisfies(filter -> {
            assertThat(filter).containsEntry("controlId", "68c2460eb75138cd755fb462");
            assertThat(filter).containsEntry("dataType", 2);
            assertThat(filter).containsEntry("filterType", 2);
            assertThat(filter).containsEntry("value", "METADULEA220626VB-AM");
        });
    }

    @Test
    void createsLeadWithActivityRelationWhenActivityRowIdExists() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadRecordRepository repo = mock(LeadRecordRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ClueIntegrationService service = new ClueIntegrationService(crm, repo, messages, objectMapper);

        when(repo.findByCustomerPhone("6288880000111")).thenReturn(Optional.empty());
        when(crm.frontendGetFilterRows(eq("imzhgl"), anyList(), eq(200), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"rows":[]}}
                        """));
        when(crm.frontendAddRow(eq("leads_bank"), anyList(), eq(true)))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":"lead-row-1"}
                        """));
        when(crm.frontendGetFilterRows(eq("leads_bank"), anyList(), eq(1), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"rows":[{"rowid":"lead-row-1","687fa4dd005dfd294df9dc3e":"6288880000111","68c24754b75138cd755fb47b":"nrgl-row-1"}]}}
                        """));

        service.createLeadForNewCustomer("6288880000111", "Yuli", "agent-row-1", "nrgl-row-1");

        ArgumentCaptor<List<Map<String, Object>>> controlsCaptor = ArgumentCaptor.forClass(List.class);
        verify(crm).frontendAddRow(eq("leads_bank"), controlsCaptor.capture(), eq(true));
        assertThat(controlsCaptor.getValue()).anySatisfy(control -> {
            assertThat(control).containsEntry("controlId", "68c24754b75138cd755fb47b");
            assertThat(control).containsEntry("value", "nrgl-row-1");
        });
    }

    @Test
    void resolvesActivityRowIdFromPersistedMessageHistory() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadRecordRepository repo = mock(LeadRecordRepository.class);
        ChatMessageRepository messages = mock(ChatMessageRepository.class);
        ClueIntegrationService service = new ClueIntegrationService(crm, repo, messages, objectMapper);
        ChatMessageEntity message = new ChatMessageEntity();
        message.setCustomerPhone("6288880000111");
        message.setReferralWelcomeText("Halo [METADULEA220626VB-AM]");
        when(messages.findRecentActivityContext(eq("6288880000111"), any(Pageable.class)))
                .thenReturn(List.of(message));
        when(crm.frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(), eq(1), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"rows":[{"rowid":"nrgl-row-1"}]}}
                        """));

        Optional<String> rowId = service.resolveActivityRowIdForCustomer("6288880000111");

        assertThat(rowId).contains("nrgl-row-1");
    }
}
