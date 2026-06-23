package com.example.aitmk.controller;

import com.example.aitmk.model.domain.AgentAccountUpsertRequest;
import com.example.aitmk.model.domain.AgentAccountView;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.security.permission.ChatPermissionService;
import com.example.aitmk.service.CrmOpenApiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentAccountControllerManagedScopeTest {
    private static final String MANAGED = "6a36b886cd23604cb4641e40";
    private final CrmOpenApiService crm = mock(CrmOpenApiService.class);
    private final ChatPermissionService permission = mock(ChatPermissionService.class);
    private final AgentAccountController controller = new AgentAccountController(crm, permission);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void authenticateOwner() {
        AuthenticatedUser owner=AuthenticatedUser.builder().accountRowId("owner").role(AgentRole.OWNER)
                .permissions(Set.copyOf(Permission.defaultsFor(AgentRole.OWNER))).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner,null,List.of()));
        when(permission.canManageAccounts(any())).thenReturn(true);
        when(crm.frontendGetFilterRows(anyString(),anyList(),anyInt(),anyInt(),anyInt(),anyList())).thenReturn(accounts());
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void createManagerWritesNormalizedCompleteCommaSeparatedScope() {
        when(crm.frontendAddRow(anyString(),anyList(),eq(true))).thenReturn(success("manager-new"));
        AgentAccountUpsertRequest request=request("MANAGER",List.of(" tmk-1 ","tmk-2","tmk-1"));request.setPassword("not-logged");
        controller.addAccount(request);
        assertThat(capturedAddControl()).isEqualTo("tmk-1,tmk-2");
    }

    @Test void updateReplacesOldScopeAndEmptyScopeWritesEmptyString() {
        when(crm.frontendEditRow(anyString(),anyString(),anyList(),eq(true))).thenReturn(success("ok"));
        controller.editAccount("manager-1",request("MANAGER",List.of("tmk-2")));
        assertThat(capturedEditControl()).isEqualTo("tmk-2");
        clearInvocations(crm); when(crm.frontendGetFilterRows(anyString(),anyList(),anyInt(),anyInt(),anyInt(),anyList())).thenReturn(accounts());
        when(crm.frontendEditRow(anyString(),anyString(),anyList(),eq(true))).thenReturn(success("ok"));
        controller.editAccount("manager-1",request("MANAGER",List.of()));
        assertThat(capturedEditControl()).isEqualTo("");
    }

    @Test void changingManagerToTmkClearsScope() {
        when(crm.frontendEditRow(anyString(),anyString(),anyList(),eq(true))).thenReturn(success("ok"));
        controller.editAccount("manager-1",request("TMK",List.of("tmk-1")));
        assertThat(capturedEditControl()).isEqualTo("");
    }

    @Test void invalidSelfAndNonTmkTargetsAreRejected() {
        assertThatThrownBy(() -> controller.editAccount("manager-1",request("MANAGER",List.of("missing")))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> controller.editAccount("manager-1",request("MANAGER",List.of("manager-1")))).hasMessageContaining("自己");
        assertThatThrownBy(() -> controller.editAccount("manager-1",request("MANAGER",List.of("owner")))).hasMessageContaining("启用的 TMK");
        assertThatThrownBy(() -> controller.editAccount("manager-1",request("MANAGER",List.of("tmk-disabled")))).hasMessageContaining("启用的 TMK");
        verify(crm,never()).frontendEditRow(anyString(),anyString(),anyList(),anyBoolean());
    }

    @Test void listAndDetailReturnPureIdsFromRelationJsonAndEmptyRelationAsEmptyList() {
        @SuppressWarnings("unchecked") Map<String,Object> listBody=(Map<String,Object>)controller.listAccounts(null,50,1).getBody();
        @SuppressWarnings("unchecked") List<AgentAccountView> rows=(List<AgentAccountView>)listBody.get("rows");
        AgentAccountView manager=rows.stream().filter(row->row.rowId().equals("manager-1")).findFirst().orElseThrow();
        AgentAccountView empty=rows.stream().filter(row->row.rowId().equals("tmk-1")).findFirst().orElseThrow();
        assertThat(manager.managedAgentIds()).containsExactly("tmk-1","tmk-2");
        assertThat(manager.managedAgentIds()).allMatch(id->!id.contains("sourcevalue")&&!id.contains("{"));
        assertThat(empty.managedAgentIds()).isEmpty();

        @SuppressWarnings("unchecked") Map<String,Object> detailBody=(Map<String,Object>)controller.getAccount("manager-1").getBody();
        assertThat(((AgentAccountView)detailBody.get("data")).managedAgentIds()).isEqualTo(manager.managedAgentIds());
    }

    private AgentAccountUpsertRequest request(String role,List<String> ids){AgentAccountUpsertRequest r=new AgentAccountUpsertRequest();r.setLoginAccount("account");r.setRole(role);r.setManagedAgentIds(ids);return r;}
    private JsonNode accounts(){return read("""
      {"success":true,"data":{"total":5,"rows":[
       {"rowid":"manager-1","69abab83433ec9f4b5e6ce0e":"manager","6a322b23cd23604cb463cc07":"MANAGER","6a322b23cd23604cb463cc08":"启用","6a36b886cd23604cb4641e40":"[{\\\"sid\\\":\\\"tmk-1\\\",\\\"name\\\":\\\"A\\\",\\\"sourcevalue\\\":\\\"x,y,z\\\"},{\\\"sid\\\":\\\"tmk-2\\\",\\\"name\\\":\\\"B\\\"}]"},
       {"rowid":"tmk-1","6a322b23cd23604cb463cc07":"TMK","6a322b23cd23604cb463cc08":"启用"},
       {"rowid":"tmk-2","6a322b23cd23604cb463cc07":"TMK","6a322b23cd23604cb463cc08":"启用"},
       {"rowid":"tmk-disabled","6a322b23cd23604cb463cc07":"TMK","6a322b23cd23604cb463cc08":"停用"},
       {"rowid":"owner","6a322b23cd23604cb463cc07":"OWNER","6a322b23cd23604cb463cc08":"启用"}
      ]}}""");}
    private JsonNode success(String data){return read("{\"success\":true,\"data\":\""+data+"\"}");}
    private JsonNode read(String value){try{return json.readTree(value);}catch(Exception e){throw new RuntimeException(e);}}
    @SuppressWarnings({"rawtypes","unchecked"}) private Object capturedAddControl(){ArgumentCaptor<List<Map<String,Object>>> c=ArgumentCaptor.forClass(List.class);verify(crm).frontendAddRow(eq("imzhgl"),c.capture(),eq(true));return control(c.getValue());}
    @SuppressWarnings({"rawtypes","unchecked"}) private Object capturedEditControl(){ArgumentCaptor<List<Map<String,Object>>> c=ArgumentCaptor.forClass(List.class);verify(crm).frontendEditRow(eq("imzhgl"),eq("manager-1"),c.capture(),eq(true));return control(c.getValue());}
    private Object control(List<Map<String,Object>> controls){return controls.stream().filter(c->MANAGED.equals(c.get("controlId"))).findFirst().orElseThrow().get("value");}
}
