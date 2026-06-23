package com.example.aitmk.service.impl;

import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.model.entity.PersistenceEnums.ConversationStatus;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.v2.RealtimeEventService;
import com.example.aitmk.service.v2.RealtimePayloadFactory;
import com.example.aitmk.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppWebhookPersistenceTest {
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @Mock AiService aiService;
    @Mock ChatHistoryService chatHistoryService;
    @Mock SendMessageService sendService;
    @Mock AgentDispatchService dispatchService;
    @Mock AgentPushService pushService;
    @Mock CrmOpenApiService crmService;
    @Mock AutoReplyScriptCacheService scriptService;
    @Mock WorkTimeService workTimeService;
    @Mock MessagePersistenceService persistenceService;
    @Mock InboundMessageRetryService inboundMessageRetryService;
    @Mock ConversationRepository conversationRepository;
    @Mock ResourceRepository resourceRepository;
    @Mock AiOrchestrationService aiOrchestrationService;
    @Mock RealtimeEventService realtimeEventService;
    @Mock RealtimePayloadFactory realtimePayloadFactory;
    @InjectMocks WhatsAppWebhookServiceImpl webhookService;

    @BeforeEach
    void defaults() {
        lenient().when(chatHistoryService.lastCustomerMessageTime(anyString())).thenReturn(Optional.empty());
        lenient().when(dispatchService.getAssignedAgent(anyString())).thenReturn(Optional.empty());
        lenient().when(dispatchService.onlineAgentsSnapshot()).thenReturn(Set.of());
        lenient().when(scriptService.firstReplyScript()).thenReturn("");
        lenient().when(inboundMessageRetryService.persist(anyString(),anyString(),any(),anyString(),anyString(),any(),any(),any(),anyString(),any(Instant.class)))
                .thenReturn(MessagePersistenceService.IncomingResult.CREATED);
    }

    @Test
    void validAssignedOnlineMessageIsPersistedThenPushedWithoutAiReply() {
        when(dispatchService.getAssignedAgent("8613800000100")).thenReturn(Optional.of("agent-a"));
        when(dispatchService.onlineAgentsSnapshot()).thenReturn(Set.of("agent-a"));
        when(crmService.addChatRecord(anyString(), anyString(), anyString(), eq("客户"), anyString())).thenReturn(true);

        webhookService.process(messagePayload("wamid.in.1", "+86 138-0000-0100", "hello"));

        verify(inboundMessageRetryService).persist(eq("8613800000100"), eq("business-1"), eq("wamid.in.1"),
                eq("text"), eq("hello"), isNull(), isNull(), isNull(), anyString(), any(Instant.class));
        verify(chatHistoryService).setCustomerNickname("8613800000100", "Alice");
        verify(dispatchService, never()).markCustomerMessageAt(anyString());
        verify(pushService).pushNewMessage(eq("agent-a"), eq("8613800000100"), any(ChatMessageRecord.class));
        verifyNoInteractions(aiService);
        verify(sendService, never()).sendTextMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void duplicateMessageStopsAllBusinessSideEffects() {
        when(inboundMessageRetryService.persist(anyString(),anyString(),eq("wamid.duplicate"),anyString(),anyString(),any(),any(),any(),anyString(),any(Instant.class)))
                .thenReturn(MessagePersistenceService.IncomingResult.DUPLICATE);
        webhookService.process(messagePayload("wamid.duplicate", "8613800000101", "duplicate"));

        verifyNoInteractions(aiService, sendService, pushService);
        verify(crmService, never()).addChatRecord(anyString(), anyString(), any(), anyString(), anyString());
        verify(dispatchService, never()).assignIfAbsent(anyString());
    }

    @Test
    void concurrentUniqueConstraintConflictAlsoStopsSideEffects() {
        when(inboundMessageRetryService.persist(anyString(),anyString(),eq("wamid.race"),anyString(),anyString(),any(),any(),any(),anyString(),any(Instant.class)))
                .thenReturn(MessagePersistenceService.IncomingResult.DUPLICATE);
        webhookService.process(messagePayload("wamid.race", "8613800000102", "race"));

        verifyNoInteractions(aiService, sendService, pushService);
        verify(chatHistoryService, never()).setCustomerNickname(anyString(), anyString());
        verify(crmService, never()).addChatRecord(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void noOnlineAgentUsesAiFallbackAfterLocalPersistenceEvenWhenCrmFails() {
        when(dispatchService.hasOnlineAgent()).thenReturn(false);
        when(crmService.addChatRecord(anyString(), anyString(), any(), eq("客户"), anyString()))
                .thenThrow(new IllegalStateException("CRM unavailable"));
        ResourceEntity mockResource = new ResourceEntity();
        mockResource.setCustomerPhone("8613800000103");
        mockResource.setId(123L);
        ConversationEntity mockConversation = new ConversationEntity();
        mockConversation.setId(456L);
        mockConversation.setResourceId(123L);
        mockConversation.setCustomerPhone("8613800000103");
        mockConversation.setAiState(AiState.NONE);
        when(resourceRepository.findByCustomerPhone("8613800000103"))
                .thenReturn(Optional.of(mockResource));
        when(conversationRepository.findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(
                eq(123L), anyCollection()))
                .thenReturn(Optional.of(mockConversation));
        webhookService.process(messagePayload("wamid.in.2", "8613800000103", "need help"));
        verify(inboundMessageRetryService).persist(anyString(), anyString(), eq("wamid.in.2"), anyString(),
                eq("need help"), any(), any(), any(), any(), any());
        verify(dispatchService).markUnassigned("8613800000103");
        verify(aiOrchestrationService).orchestrate(eq("business-1"), eq("8613800000103"), eq("need help"),
                any(ConversationEntity.class), any(ResourceEntity.class));
    }

    @Test
    void crmFailureDoesNotPreventAssignedAgentPush() {
        when(dispatchService.getAssignedAgent("8613800000105")).thenReturn(Optional.of("agent-a"));
        when(dispatchService.onlineAgentsSnapshot()).thenReturn(Set.of("agent-a"));
        when(crmService.addChatRecord(anyString(), anyString(), anyString(), eq("客户"), anyString()))
                .thenThrow(new IllegalStateException("CRM unavailable"));

        webhookService.process(messagePayload("wamid.crm-failed", "8613800000105", "still push"));

        verify(pushService).pushNewMessage(eq("agent-a"), eq("8613800000105"),
                argThat(message -> "still push".equals(message.getMessage())));
        verify(dispatchService, never()).markCustomerMessageAt(anyString());
        verifyNoInteractions(aiService);
    }

    @Test
    void statusOnlyWebhookUpdatesKnownStatesAndIgnoresUnknownState() {
        webhookService.process(statusPayload("wamid.out.1", "read", "1710000000"));
        verify(persistenceService).updateDeliveryStatus(eq("wamid.out.1"), eq(SentStatus.READ),
                eq(Instant.ofEpochSecond(1710000000L)), isNull());

        clearInvocations(persistenceService);
        webhookService.process(statusPayload("wamid.out.1", "accepted", "bad-timestamp"));
        verifyNoInteractions(persistenceService);
    }

    @Test
    void malformedPayloadNullEntryAndBlankPhoneAreSafelyIgnored() {
        webhookService.process("not-json");
        webhookService.process("{\"object\":\"whatsapp_business_account\"}");
        webhookService.process(messagePayload("wamid.blank", "---", "ignored"));

        verifyNoInteractions(persistenceService, inboundMessageRetryService, aiService, sendService, pushService);
    }

    @Test
    void mediaPayloadPersistsMediaMetadataAndDisplayContent() {
        when(dispatchService.hasOnlineAgent()).thenReturn(false);
        when(crmService.addChatRecord(anyString(), anyString(), any(), eq("\u5ba2\u6237"), anyString())).thenReturn(true);
        ResourceEntity mockResource = new ResourceEntity();
        mockResource.setCustomerPhone("8613800000104");
        mockResource.setId(124L);
        ConversationEntity mockConversation = new ConversationEntity();
        mockConversation.setId(457L);
        mockConversation.setResourceId(124L);
        mockConversation.setCustomerPhone("8613800000104");
        mockConversation.setAiState(AiState.NONE);
        when(resourceRepository.findByCustomerPhone("8613800000104"))
                .thenReturn(Optional.of(mockResource));
        when(conversationRepository.findFirstByResourceIdAndStatusInOrderByCreatedAtDesc(
                eq(124L), anyCollection()))
                .thenReturn(Optional.of(mockConversation));
        webhookService.process(mediaPayload());

        verify(inboundMessageRetryService).persist(eq("8613800000104"), eq("business-1"), eq("wamid.media"),
                eq("image"), contains("mediaId=media-1"), eq("media-1"), eq("https://media.example/image"),
                eq("image/jpeg"), anyString(), any(Instant.class));
    }

    private String messagePayload(String id, String from, String body) {
        return """
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"business-1"},
                  "contacts":[{"wa_id":"%s","profile":{"name":"Alice"}}],
                  "messages":[{"id":"%s","from":"%s","timestamp":"1710000000","type":"text","text":{"body":"%s"}}]
                }}]}]}
                """.formatted(from.replaceAll("[^0-9]", ""), id, from, body);
    }

    private String statusPayload(String id, String status, String timestamp) {
        return """
                {"entry":[{"changes":[{"field":"messages","value":{
                  "statuses":[{"id":"%s","status":"%s","timestamp":"%s"}]
                }}]}]}
                """.formatted(id, status, timestamp);
    }

    private String mediaPayload() {
        return """
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"business-1"},
                  "messages":[{"id":"wamid.media","from":"8613800000104","timestamp":"1710000000","type":"image",
                    "image":{"id":"media-1","url":"https://media.example/image","mime_type":"image/jpeg"}}]
                }}]}]}
                """;
    }
}
