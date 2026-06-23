package com.example.aitmk;

import com.example.aitmk.model.api.v2.V2Api;
import com.example.aitmk.model.api.v2.V2Exception;
import com.example.aitmk.model.entity.*;
import com.example.aitmk.model.entity.PersistenceEnums.MessageType;
import com.example.aitmk.model.entity.PersistenceEnums.SentStatus;
import com.example.aitmk.repository.*;
import com.example.aitmk.security.auth.*;
import com.example.aitmk.service.AgentDispatchService;
import com.example.aitmk.service.ConversationService;
import com.example.aitmk.service.MessagePersistenceService;
import com.example.aitmk.service.v2.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RealtimeEventContractIntegrationTest {
    @Autowired ResourceRepository resources;
    @Autowired ConversationRepository conversations;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationAgentStateRepository states;
    @Autowired RealtimeEventRepository eventRepository;
    @Autowired MessageCommandService messageCommands;
    @Autowired ConversationCommandService conversationCommands;
    @Autowired MessagePersistenceService messagePersistence;
    @Autowired ConversationService conversationService;
    @Autowired RealtimePayloadFactory payloads;
    @Autowired RealtimeEventService events;
    @Autowired AgentDispatchService dispatch;
    @Autowired ObjectMapper json;

    private String onlineAgent;

    @AfterEach
    void clearOnlineAgent() {
        if (onlineAgent != null) dispatch.markOffline(onlineAgent);
    }

    @Test
    void outgoingCreatedStatusChangedAndClosedEventsContainCompleteDtos() throws Exception {
        Fixture fixture = fixture("agent-rt");
        AuthenticatedUser user = user(fixture.agent());

        V2Api.MessageView sent = messageCommands.send(fixture.conversation().getId(), "rt-" + UUID.randomUUID(),
                new V2Api.SendMessageRequest("IMAGE", "caption",
                        new V2Api.MessageMediaRequest("media-1", "photo.jpg", "image/jpeg"), null), user);
        JsonNode created = payload(lastEvent(fixture, "MESSAGE_CREATED"));
        assertCompleteMessage(created, sent.messageId(), "PENDING");
        assertThat(created.path("media").path("mediaId").asText()).isEqualTo("media-1");
        assertThat(created.path("media").path("fileName").asText()).isEqualTo("photo.jpg");
        assertThat(created.has("messageId") && created.has("conversationId") && created.has("createdAt")).isTrue();

        messagePersistence.markOutgoingSent(Long.valueOf(sent.messageId()), "wamid.rt.status", Instant.now());
        messagePersistence.updateDeliveryStatus("wamid.rt.status", SentStatus.READ, Instant.now(), null);
        JsonNode status = payload(lastEvent(fixture, "MESSAGE_STATUS_CHANGED"));
        assertCompleteMessage(status, sent.messageId(), "READ");
        assertThat(status.path("readAt").isTextual()).isTrue();

        ConversationEntity current = conversations.findById(fixture.conversation().getId()).orElseThrow();
        conversationCommands.close(current.getId(), new V2Api.CloseRequest("RESOLVED", "done", current.getVersion()), user);
        RealtimeEventEntity closedEvent = lastEvent(fixture, "CONVERSATION_UPDATED");
        JsonNode closed = payload(closedEvent);
        assertCompleteConversation(closed, fixture);
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("closedAt").isTextual()).isTrue();
        assertThat(closed.path("closeReason").asText()).isEqualTo("RESOLVED - done");
        assertThat(closed.path("version").asLong()).isEqualTo(closedEvent.getAggregateVersion());
    }

    @Test
    void conversationCreatedIsACompleteImmutableSnapshot() throws Exception {
        ResourceEntity resource = resource("agent-created");
        ConversationEntity created = conversationService.getOrCreateActive(resource, "business", "META");
        RealtimeEventEntity event = lastEvent(created.getId(), "CONVERSATION_CREATED");
        String snapshot = event.getPayloadJson();
        JsonNode node = payload(event);
        assertThat(node.path("conversationId").asText()).isEqualTo(created.getId().toString());
        assertThat(node.has("customer") && node.has("lastMessage") && node.has("version")).isTrue();

        created.setStatus(PersistenceEnums.ConversationStatus.CLOSED);
        conversations.saveAndFlush(created);
        assertThat(eventRepository.findById(event.getId()).orElseThrow().getPayloadJson()).isEqualTo(snapshot);
    }

    @Test
    void conversationPayloadUsesTargetSpecificUnreadState() throws Exception {
        Fixture fixture = fixture("agent-a");
        Long firstRead = message(fixture, "read-a").getId();
        Long secondRead = message(fixture, "read-b").getId();
        saveState(fixture.conversation().getId(), "agent-a", 2, firstRead);
        saveState(fixture.conversation().getId(), "agent-b", 7, secondRead);
        var first = events.append("CONVERSATION_UPDATED", "CONVERSATION", fixture.conversation().getId(),
                fixture.resource().getId(), fixture.conversation().getId(), "agent-a", fixture.conversation().getVersion(),
                payloads.conversation(fixture.conversation(), "agent-a"));
        var second = events.append("CONVERSATION_UPDATED", "CONVERSATION", fixture.conversation().getId(),
                fixture.resource().getId(), fixture.conversation().getId(), "agent-b", fixture.conversation().getVersion(),
                payloads.conversation(fixture.conversation(), "agent-b"));

        assertThat(payload(first).path("unreadCount").asLong()).isEqualTo(2);
        assertThat(payload(first).path("lastReadMessageId").asText()).isEqualTo(firstRead.toString());
        assertThat(payload(second).path("unreadCount").asLong()).isEqualTo(7);
        assertThat(payload(second).path("lastReadMessageId").asText()).isEqualTo(secondRead.toString());
    }

    @Test
    void assignmentChangedIsWrittenForOldAndTargetAgents() {
        Fixture fixture = fixture("agent-old");
        onlineAgent = "agent-new";
        dispatch.markOnline(onlineAgent);
        AuthenticatedUser owner = user("owner");
        conversationCommands.transfer(fixture.conversation().getId(),
                new V2Api.TransferRequest(onlineAgent, "handoff", fixture.conversation().getVersion()), owner);

        List<RealtimeEventEntity> assignmentEvents = eventRepository
                .findByConversationIdAndEventTypeOrderByIdAsc(fixture.conversation().getId(), "ASSIGNMENT_CHANGED");
        assertThat(assignmentEvents).extracting(RealtimeEventEntity::getTargetAgentId)
                .containsExactlyInAnyOrder("agent-old", "agent-new");
        assertThat(assignmentEvents).allSatisfy(event -> {
            try {
                assertThat(payload(event).path("fromAgentId").asText()).isEqualTo("agent-old");
                assertThat(payload(event).path("targetAgentId").asText()).isEqualTo("agent-new");
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }

    @Test
    void recoveryAndSocketViewShareTheSameSnapshotAndCorruptionIsRejected() {
        Fixture fixture = fixture("agent-recover");
        V2Api.ConversationSummary snapshot = payloads.conversation(fixture.conversation(), fixture.agent());
        RealtimeEventEntity event = events.append("CONVERSATION_UPDATED", "CONVERSATION",
                fixture.conversation().getId(), fixture.resource().getId(), fixture.conversation().getId(), fixture.agent(),
                fixture.conversation().getVersion(), snapshot);
        eventRepository.flush();

        V2Api.EventView socketView = events.view(event);
        V2Api.EventView recovered = events.recover(fixture.agent(), null, 100).items().stream()
                .filter(item -> item.eventId().equals(event.getEventId())).findFirst().orElseThrow();
        JsonNode socketNode = json.valueToTree(socketView);
        JsonNode recoveredNode = json.valueToTree(recovered);
        assertThat(socketNode).isEqualTo(recoveredNode);

        RealtimeEventEntity corrupted = rawEvent(fixture, "agent-corrupt", "{broken");
        eventRepository.saveAndFlush(corrupted);
        assertThatThrownBy(() -> events.recover("agent-corrupt", null, 10))
                .isInstanceOfSatisfying(V2Exception.class,
                        error -> assertThat(error.getCode()).isEqualTo("EVENT_PAYLOAD_CORRUPTED"));
        assertThat(corrupted.getPublishedAt()).isNull();
    }

    private Fixture fixture(String agent) {
        ResourceEntity resource = resource(agent);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setResourceId(resource.getId());
        conversation.setCustomerPhone(resource.getCustomerPhone());
        conversation.setAssignedAgentId(agent);
        conversation = conversations.saveAndFlush(conversation);
        return new Fixture(resource, conversation, agent);
    }

    private ResourceEntity resource(String agent) {
        ResourceEntity resource = new ResourceEntity();
        resource.setCustomerPhone("rt" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        resource.setCustomerName("Alice");
        resource.setAssignedAgentId(agent);
        resource.setResourceStatus(PersistenceEnums.ResourceStatus.ASSIGNED);
        resource.setLastCustomerMessageAt(Instant.now());
        resource.setLastMessageAt(Instant.now());
        return resources.saveAndFlush(resource);
    }

    private void saveState(Long conversationId, String agent, long unread, Long lastRead) {
        ConversationAgentStateEntity state = new ConversationAgentStateEntity();
        state.setConversationId(conversationId);
        state.setAgentId(agent);
        state.setUnreadCount(unread);
        state.setLastReadMessageId(lastRead);
        states.saveAndFlush(state);
    }

    private ChatMessageEntity message(Fixture fixture, String content) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(fixture.conversation().getId());
        message.setResourceId(fixture.resource().getId());
        message.setCustomerPhone(fixture.resource().getCustomerPhone());
        message.setSenderType(PersistenceEnums.SenderType.CUSTOMER);
        message.setMessageType(MessageType.TEXT);
        message.setContent(content);
        return messages.saveAndFlush(message);
    }

    private RealtimeEventEntity rawEvent(Fixture fixture, String target, String payload) {
        RealtimeEventEntity event = new RealtimeEventEntity();
        event.setEventType("MESSAGE_CREATED");
        event.setAggregateType("MESSAGE");
        event.setAggregateId(1L);
        event.setResourceId(fixture.resource().getId());
        event.setConversationId(fixture.conversation().getId());
        event.setTargetAgentId(target);
        event.setPayloadJson(payload);
        return event;
    }

    private RealtimeEventEntity lastEvent(Fixture fixture, String type) {
        return lastEvent(fixture.conversation().getId(), type);
    }

    private RealtimeEventEntity lastEvent(Long conversationId, String type) {
        List<RealtimeEventEntity> rows = eventRepository
                .findByConversationIdAndEventTypeOrderByIdAsc(conversationId, type);
        return rows.get(rows.size() - 1);
    }

    private JsonNode payload(RealtimeEventEntity event) throws Exception {
        return json.readTree(event.getPayloadJson());
    }

    private void assertCompleteMessage(JsonNode node, String messageId, String status) {
        assertThat(node.path("messageId").asText()).isEqualTo(messageId);
        assertThat(node.path("conversationId").isTextual()).isTrue();
        assertThat(node.path("resourceId").isTextual()).isTrue();
        assertThat(node.path("messageType").isTextual()).isTrue();
        assertThat(node.path("senderType").isTextual()).isTrue();
        assertThat(node.path("sentStatus").asText()).isEqualTo(status);
    }

    private void assertCompleteConversation(JsonNode node, Fixture fixture) {
        assertThat(node.path("conversationId").asText()).isEqualTo(fixture.conversation().getId().toString());
        assertThat(node.path("resourceId").asText()).isEqualTo(fixture.resource().getId().toString());
        assertThat(node.path("customer").path("phone").asText()).isEqualTo(fixture.resource().getCustomerPhone());
        assertThat(node.has("channel") && node.has("resourceStatus") && node.has("aiState")).isTrue();
        assertThat(node.has("assignedAgent") && node.has("replyable") && node.has("unreadCount")).isTrue();
        assertThat(node.has("lastMessage") && node.has("startedAt") && node.has("version")).isTrue();
    }

    private AuthenticatedUser user(String id) {
        return AuthenticatedUser.builder().accountRowId(id).role(AgentRole.OWNER)
                .permissions(Set.copyOf(Permission.defaultsFor(AgentRole.OWNER))).build();
    }

    private record Fixture(ResourceEntity resource, ConversationEntity conversation, String agent) {}
}
