package com.example.aitmk;

import com.example.aitmk.model.entity.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.MessagePersistenceService;
import com.example.aitmk.service.impl.InboundMessageRetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest @ActiveProfiles("test")
class InboundMessageConcurrencyIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationAgentStateRepository states;
    @Autowired RealtimeEventRepository events;
    @Autowired InboundMessageRetryService retry;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private final List<Fixture> fixtures = new ArrayList<>();

    @AfterEach void removeCommittedThreadFixtures() {
        for (int i = fixtures.size() - 1; i >= 0; i--) {
            Fixture f = fixtures.get(i);
            jdbc.update("delete from realtime_event where conversation_id=?", f.conversationId());
            jdbc.update("delete from conversation_agent_state where conversation_id=?", f.conversationId());
            jdbc.update("delete from chat_message where conversation_id=?", f.conversationId());
            jdbc.update("delete from assignment_record where conversation_id=?", f.conversationId());
            jdbc.update("delete from conversation where id=?", f.conversationId());
            jdbc.update("delete from business_resource where id=?", f.resourceId());
        }
        fixtures.clear();
    }

    @Test void distinctConcurrentWebhookMessagesBothCommitAndAdvanceStateAtomically() throws Exception {
        Fixture f=fixture();Instant older=Instant.parse("2026-06-22T02:00:00.123456Z"),newer=older.plusNanos(1_000);
        List<MessagePersistenceService.IncomingResult> results=concurrent(
                ()->persist(f,"wamid."+f.phone()+".1",older),()->persist(f,"wamid."+f.phone()+".2",newer));
        assertThat(results).containsOnly(MessagePersistenceService.IncomingResult.CREATED).hasSize(2);
        List<ChatMessageEntity> rows=messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc(f.phone());
        assertThat(rows).hasSize(2).extracting(ChatMessageEntity::getExternalMessageId)
                .containsExactlyInAnyOrder("wamid."+f.phone()+".1","wamid."+f.phone()+".2");
        ConversationEntity conversation=conversations.findById(f.conversationId()).orElseThrow();
        ResourceEntity resource=resources.findById(f.resourceId()).orElseThrow();
        assertThat(conversation.getLastMessageAt()).isEqualTo(newer);
        assertThat(resource.getLastCustomerMessageAt()).isEqualTo(newer);
        assertThat(states.findByConversationIdAndAgentId(f.conversationId(),f.agent()).orElseThrow().getUnreadCount()).isEqualTo(2);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(),"MESSAGE_CREATED")).isEqualTo(2);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(),"UNREAD_COUNT_CHANGED")).isEqualTo(2);
        events.findByConversationIdAndEventTypeOrderByIdAsc(f.conversationId(),"MESSAGE_CREATED").forEach(event -> {
            try {
                var payload=json.readTree(event.getPayloadJson());
                assertThat(payload.path("messageId").isTextual()).isTrue();
                assertThat(payload.path("conversationId").asText()).isEqualTo(f.conversationId().toString());
                assertThat(payload.path("resourceId").asText()).isEqualTo(f.resourceId().toString());
                assertThat(payload.path("senderType").asText()).isEqualTo("CUSTOMER");
                assertThat(payload.path("sentStatus").asText()).isEqualTo("DELIVERED");
            } catch(Exception ex){throw new AssertionError(ex);}
        });
    }

    @Test void sameExternalMessageIdCommitsOnceAndCreatesSideEffectsOnce() throws Exception {
        Fixture f=fixture();String external="wamid."+f.phone()+".same";Instant at=Instant.parse("2026-06-22T02:01:00.654321Z");
        List<MessagePersistenceService.IncomingResult> results=concurrent(
                ()->persist(f,external,at),()->persist(f,external,at));
        assertThat(results).containsExactlyInAnyOrder(MessagePersistenceService.IncomingResult.CREATED,MessagePersistenceService.IncomingResult.DUPLICATE);
        assertThat(messages.findByExternalMessageId(external)).isPresent();
        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc(f.phone())).hasSize(1);
        assertThat(states.findByConversationIdAndAgentId(f.conversationId(),f.agent()).orElseThrow().getUnreadCount()).isEqualTo(1);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(),"MESSAGE_CREATED")).isEqualTo(1);
        assertThat(events.countByConversationIdAndEventType(f.conversationId(),"UNREAD_COUNT_CHANGED")).isEqualTo(1);
    }

    @Test void inboundMediaCreatedEventContainsNestedMediaView() throws Exception {
        Fixture f=fixture();String external="wamid."+f.phone()+".media";Instant at=Instant.parse("2026-06-22T02:02:00.123456Z");
        assertThat(retry.persist(f.phone(),"business",external,"image","photo", "media-1",
                "https://mock/media-1","image/jpeg","{}",at)).isEqualTo(MessagePersistenceService.IncomingResult.CREATED);
        var event=events.findByConversationIdAndEventTypeOrderByIdAsc(f.conversationId(),"MESSAGE_CREATED").get(0);
        var payload=json.readTree(event.getPayloadJson());
        assertThat(payload.path("media").path("mediaId").asText()).isEqualTo("media-1");
        assertThat(payload.path("media").path("mediaUrl").asText()).isEqualTo("https://mock/media-1");
        assertThat(payload.path("media").path("mimeType").asText()).isEqualTo("image/jpeg");
        assertThat(payload.has("mediaId")).isFalse();
    }

    private MessagePersistenceService.IncomingResult persist(Fixture f,String external,Instant at){return retry.persist(f.phone(),"business",external,"text","same",null,null,null,"{}",at);}
    private List<MessagePersistenceService.IncomingResult> concurrent(Callable<MessagePersistenceService.IncomingResult> a,Callable<MessagePersistenceService.IncomingResult> b)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);try{Future<MessagePersistenceService.IncomingResult> x=pool.submit(()->{start.await();return a.call();});Future<MessagePersistenceService.IncomingResult> y=pool.submit(()->{start.await();return b.call();});start.countDown();return List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
    private Fixture fixture(){String phone="86"+UUID.randomUUID().toString().replace("-","").substring(0,18);String agent="agent-"+phone.substring(phone.length()-6);ResourceEntity r=new ResourceEntity();r.setCustomerPhone(phone);r.setAssignedAgentId(agent);r=resources.saveAndFlush(r);ConversationEntity c=new ConversationEntity();c.setResourceId(r.getId());c.setCustomerPhone(phone);c.setAssignedAgentId(agent);c=conversations.saveAndFlush(c);Fixture fixture=new Fixture(phone,agent,r.getId(),c.getId());fixtures.add(fixture);return fixture;}
    private record Fixture(String phone,String agent,Long resourceId,Long conversationId){}
}
