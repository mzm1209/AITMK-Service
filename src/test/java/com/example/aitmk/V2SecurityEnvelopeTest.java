package com.example.aitmk;

import com.example.aitmk.model.domain.CrmAgentAccount;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class V2SecurityEnvelopeTest {
 private static final String DASHBOARD_OWNER_ID = "dashboard-contract-owner";
 @Autowired MockMvc mvc; @Autowired JwtTokenService tokens; @Autowired ApplicationContext context;
 @Autowired ResourceRepository resources; @Autowired ConversationRepository conversations;

 @Test void unauthenticatedV2RequestUsesStandardEnvelope()throws Exception{mvc.perform(get("/api/v2/conversations")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value("UNAUTHORIZED")).andExpect(jsonPath("$.error.details").doesNotExist()).andExpect(jsonPath("$.requestId").isNotEmpty());}

 @Test void dashboardHttpContractUsesPluralNamesSecondsAndRealValues()throws Exception{
  Instant firstCustomerMessageAt=Instant.now().minusSeconds(35);
  Instant firstAgentReplyAt=firstCustomerMessageAt.plusSeconds(5);
  var resource=new ResourceEntity();resource.setCustomerPhone("dashboard-contract-phone");resource.setResourceStatus(PersistenceEnums.ResourceStatus.ASSIGNED);resource.setAssignedAgentId(DASHBOARD_OWNER_ID);resource.setLastCustomerMessageAt(Instant.now());resource=resources.saveAndFlush(resource);
  var conversation=new ConversationEntity();conversation.setResourceId(resource.getId());conversation.setCustomerPhone(resource.getCustomerPhone());conversation.setAssignedAgentId(DASHBOARD_OWNER_ID);conversation.setStatus(PersistenceEnums.ConversationStatus.HUMAN_ACTIVE);conversation.setFirstCustomerMessageAt(firstCustomerMessageAt);conversation.setFirstAgentReplyAt(firstAgentReplyAt);conversations.saveAndFlush(conversation);
  mvc.perform(get("/api/v2/dashboard/summary?scope=mine").header("Authorization","Bearer "+ownerToken(DASHBOARD_OWNER_ID))).andExpect(status().isOk()).andExpect(jsonPath("$.data.activeConversations").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1))).andExpect(jsonPath("$.data.pendingAssignments").exists()).andExpect(jsonPath("$.data.expiringReplyWindows").exists()).andExpect(jsonPath("$.data.todayReceived").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1))).andExpect(jsonPath("$.data.firstHumanResponseP50Seconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(4.0))).andExpect(jsonPath("$.data.firstHumanResponseP50Ms").doesNotExist());
 }

 @Test void invalidCursorReturnsSafeSpecificError()throws Exception{mvc.perform(get("/api/v2/conversations?cursor=not-a-valid-cursor").header("Authorization","Bearer "+ownerToken())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("CURSOR_INVALID")).andExpect(jsonPath("$.error.message").value("游标无效或已过期")).andExpect(jsonPath("$.requestId").isNotEmpty());}

 @Test void integrationProfileDoesNotRegisterAnyScheduler(){assertThat(context.containsBean("agentInactiveScheduler")).isFalse();assertThat(context.containsBean("conversationTimeoutScheduler")).isFalse();assertThat(context.containsBean("realtimeEventPublisher")).isFalse();}

 private String ownerToken(){return ownerToken("owner-1");}
 private String ownerToken(String rowId){return tokens.generateToken(CrmAgentAccount.builder().rowId(rowId).loginAccount("owner").role(AgentRole.OWNER).enabled(true).build());}
}
