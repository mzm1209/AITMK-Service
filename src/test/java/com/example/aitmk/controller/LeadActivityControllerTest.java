package com.example.aitmk.controller;

import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeadActivityControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void listsActivitiesWithoutKeywordAndMapsRows() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadActivityController controller = new LeadActivityController(crm);
        when(crm.frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(), eq(50), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"total":1,"rows":[{"rowid":"activity-row-1","68c2460eb75138cd755fb462":"METADULEA220626VB-AM","68c2460eb75138cd755fb463":"Adult Mandarin"}]}}
                        """));

        ResponseEntity<?> response = controller.activities(null, 50, 1);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("total")).isEqualTo(1);
        List<?> rows = (List<?>) body.get("rows");
        assertThat(rows).singleElement().satisfies(row -> {
            Map<?, ?> item = (Map<?, ?>) row;
            assertThat(item.get("rowId")).isEqualTo("activity-row-1");
            assertThat(item.get("name")).isEqualTo("METADULEA220626VB-AM");
            assertThat(item.get("tips")).isEqualTo("Adult Mandarin");
        });

        ArgumentCaptor<List<Map<String, Object>>> filtersCaptor = ArgumentCaptor.forClass(List.class);
        verify(crm).frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), filtersCaptor.capture(),
                eq(50), eq(1), eq(0), anyList());
        assertThat(filtersCaptor.getValue()).isEmpty();
    }

    @Test
    void keywordUsesNameFilter() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadActivityController controller = new LeadActivityController(crm);
        when(crm.frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(), eq(20), eq(2), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"total":0,"rows":[]}}
                        """));

        controller.activities("METADULEA", 20, 2);

        ArgumentCaptor<List<Map<String, Object>>> filtersCaptor = ArgumentCaptor.forClass(List.class);
        verify(crm).frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), filtersCaptor.capture(),
                eq(20), eq(2), eq(0), anyList());
        assertThat(filtersCaptor.getValue()).singleElement().satisfies(filter -> {
            assertThat(filter).containsEntry("controlId", "68c2460eb75138cd755fb462");
            assertThat(filter).containsEntry("dataType", 2);
            assertThat(filter).containsEntry("spliceType", 1);
            assertThat(filter).containsEntry("filterType", 7);
            assertThat(filter).containsEntry("value", "METADULEA");
        });
    }

    @Test
    void pageSizeIsCappedAtTwoHundred() throws Exception {
        CrmOpenApiService crm = mock(CrmOpenApiService.class);
        LeadActivityController controller = new LeadActivityController(crm);
        when(crm.frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(), eq(200), eq(1), eq(0), anyList()))
                .thenReturn(objectMapper.readTree("""
                        {"success":true,"data":{"total":0,"rows":[]}}
                        """));

        controller.activities(null, 500, -1);

        verify(crm).frontendGetFilterRows(eq("68c2460eb75138cd755fb461"), anyList(),
                eq(200), eq(1), eq(0), anyList());
    }
}
