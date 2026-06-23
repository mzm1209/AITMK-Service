package com.example.aitmk;

import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.entity.ConversationEntity;
import com.example.aitmk.model.entity.ResourceEntity;
import com.example.aitmk.repository.ChatMessageRepository;
import com.example.aitmk.repository.ConversationAgentStateRepository;
import com.example.aitmk.repository.ConversationRepository;
import com.example.aitmk.repository.RealtimeEventRepository;
import com.example.aitmk.repository.ResourceRepository;
import com.example.aitmk.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class WhatsAppWebhookConcurrencyIntegrationTest {
    @Autowired WhatsAppWebhookService webhook;
    @Autowired AgentDispatchService dispatch;
    @Autowired ChatHistoryService chatHistory;
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationAgentStateRepository states;
    @Autowired RealtimeEventRepository events;
    @Autowired JdbcTemplate jdbc;

    @MockBean CrmOpenApiService crm;
    @MockBean AgentPushService push;
    @MockBean AiService ai;
    @MockBean SendMessageService send;
    @MockBean AutoReplyScriptCacheService scripts;
    @MockBean WorkTimeService workTime;

    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach
    void cleanup() throws InterruptedException {
        // process() is asynchronous; give a failed assertion path a short chance to leave the fixture.
        Thread.sleep(100);
        for (int i = fixtures.size() - 1; i >= 0; i--) {
            Fixture f = fixtures.get(i);
            dispatch.markOffline(f.agent());
            jdbc.update("delete from realtime_event where conversation_id=?", f.conversationId());
            jdbc.update("delete from conversation_agent_state where conversation_id=?", f.conversationId());
            jdbc.update("delete from chat_message where conversation_id=?", f.conversationId());
            jdbc.update("delete from assignment_record where conversation_id=?", f.conversationId());
            jdbc.update("delete from conversation where id=?", f.conversationId());
            jdbc.update("delete from business_resource where id=?", f.resourceId());
        }
        fixtures.clear();
    }

    @Test
    void differentExternalIdsCompleteTheEntireWebhookPipelineTwice() throws Exception {
        Fixture f = fixture();
        when(crm.addChatRecord(anyString(), eq(f.phone()), eq(f.agent()), eq("客户"), eq("same-content")))
                .thenReturn(true);

        submitTogether(payload(f.phone(), "wamid." + f.phone() + ".1", "same-content"),
                payload(f.phone(), "wamid." + f.phone() + ".2", "same-content"));

        verify(push, timeout(10_000).times(2)).pushNewMessage(eq(f.agent()), eq(f.phone()),
                argThat(message -> "same-content".equals(message.getMessage())));
        verify(crm, times(2)).addChatRecord(anyString(), eq(f.phone()), eq(f.agent()), eq("客户"), eq("same-content"));
        verifyNoInteractions(ai);

        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc(f.phone()))
                .hasSize(2)
                .extracting(message -> message.getExternalMessageId())
                .containsExactlyInAnyOrder("wamid." + f.phone() + ".1", "wamid." + f.phone() + ".2");
        assertThat(states.findByConversationIdAndAgentId(f.conversationId(), f.agent()).orElseThrow().getUnreadCount())
                .isEqualTo(2);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(), "MESSAGE_CREATED")).isEqualTo(2);
    }

    @Test
    void sameExternalIdCompletesTheEntireWebhookPipelineOnlyOnce() throws Exception {
        Fixture f = fixture();
        String externalId = "wamid." + f.phone() + ".duplicate";
        when(crm.addChatRecord(anyString(), eq(f.phone()), eq(f.agent()), eq("客户"), eq("same-content")))
                .thenReturn(true);

        String payload = payload(f.phone(), externalId, "same-content");
        submitTogether(payload, payload);

        verify(push, timeout(10_000).times(1)).pushNewMessage(eq(f.agent()), eq(f.phone()), any(ChatMessageRecord.class));
        Thread.sleep(300);
        verify(push, times(1)).pushNewMessage(eq(f.agent()), eq(f.phone()), any(ChatMessageRecord.class));
        verify(crm, times(1)).addChatRecord(anyString(), eq(f.phone()), eq(f.agent()), eq("客户"), eq("same-content"));
        verifyNoInteractions(ai);

        assertThat(messages.findByExternalMessageId(externalId)).isPresent();
        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc(f.phone())).hasSize(1);
        assertThat(states.findByConversationIdAndAgentId(f.conversationId(), f.agent()).orElseThrow().getUnreadCount())
                .isEqualTo(1);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(), "MESSAGE_CREATED")).isEqualTo(1);
    }

    @Test
    void unchangedNicknameDoesNotAdvanceResourceVersion() {
        Fixture f = fixture();
        ResourceEntity resource = resources.findById(f.resourceId()).orElseThrow();
        resource.setCustomerName("Alice");
        resources.saveAndFlush(resource);
        long version = resources.findById(f.resourceId()).orElseThrow().getVersion();

        chatHistory.setCustomerNickname(f.phone(), " Alice ");

        ResourceEntity unchanged = resources.findById(f.resourceId()).orElseThrow();
        assertThat(unchanged.getCustomerName()).isEqualTo("Alice");
        assertThat(unchanged.getVersion()).isEqualTo(version);
    }

    private Fixture fixture() {
        String phone = "86" + String.format("%012d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000_000L));
        String agent = "agent-" + phone.substring(phone.length() - 6);
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone(phone);
        resource.setAssignedAgentId(agent);
        resource.setLastMessageAt(Instant.now());
        resource = resources.saveAndFlush(resource);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(phone);
        conversation.setAssignedAgentId(agent);
        conversation.setLastMessageAt(Instant.now());
        conversation = conversations.saveAndFlush(conversation);
        dispatch.markOnline(agent);
        Fixture fixture = new Fixture(phone, agent, resource.getId(), conversation.getId());
        fixtures.add(fixture);
        return fixture;
    }

    private void submitTogether(String first, String second) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            callers.submit(() -> { await(start); webhook.process(first); });
            callers.submit(() -> { await(start); webhook.process(second); });
            start.countDown();
            callers.shutdown();
            assertThat(callers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            callers.shutdownNow();
        }
    }

    private void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private String payload(String phone, String externalId, String content) {
        return """
                {"entry":[{"changes":[{"field":"messages","value":{
                  "metadata":{"phone_number_id":"mock-business"},
                  "contacts":[{"wa_id":"%s","profile":{"name":"Alice"}}],
                  "messages":[{"id":"%s","from":"%s","timestamp":"%d","type":"text","text":{"body":"%s"}}]
                }}]}]}
                """.formatted(phone, externalId, phone, Instant.now().getEpochSecond(), content);
    }

    private record Fixture(String phone, String agent, Long resourceId, Long conversationId) {}
}
