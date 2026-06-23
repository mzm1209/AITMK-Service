package com.example.aitmk;

import com.example.aitmk.model.domain.ChatMessageRecord;
import com.example.aitmk.model.entity.PersistenceEnums.*;
import com.example.aitmk.repository.*;
import com.example.aitmk.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceIntegrationTest {
    @Autowired ChatHistoryService history;
    @Autowired MessagePersistenceService messagePersistence;
    @Autowired AgentDispatchService dispatch;
    @Autowired ChatMessageRepository messages;
    @Autowired ConversationRepository conversations;
    @Autowired AssignmentRecordRepository assignments;
    @Autowired ResourceRepository resources;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        assignments.deleteAll();
        messages.deleteAll();
        conversations.deleteAll();
        resources.deleteAll();
        dispatch.replaceState(java.util.Set.of(), Map.of());
    }

    @Test
    void flywayCreatesAllBusinessTablesAndGeneratedActiveAssignmentColumn() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);
        assertThat(tables).contains("business_resource", "conversation", "chat_message", "assignment_record");
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'assignment_record'",
                String.class);
        assertThat(columns).contains("active_resource_id");
    }

    @Test
    void migrationHistoryNicknameAndPagingWorkTogether() {
        history.recordCustomerMessage("8613800000001", "hello");
        history.recordAiReply("8613800000001", "welcome");
        history.setCustomerNickname("8613800000001", "Alice");

        assertThat(history.listCustomers()).singleElement().satisfies(customer -> {
            assertThat(customer.getCustomerNickname()).isEqualTo("Alice");
            assertThat(customer.getLastMessage()).isEqualTo("welcome");
        });
        assertThat(history.listMessagesPaged("8613800000001", 1, 1, true).getItems())
                .singleElement().extracting(ChatMessageRecord::getMessage).isEqualTo("welcome");
        assertThat(history.lastCustomerMessageTime("8613800000001")).isPresent();
    }

    @Test
    void activeConversationIsReusedAndFirstReplyTimesAreOnlyInitializedOnce() {
        history.recordCustomerMessage("8613800000010", "first");
        history.recordCustomerMessage("8613800000010", "second");
        history.recordAiReply("8613800000010", "ai-1");
        Instant firstAiAt = conversations.findByCustomerPhoneOrderByCreatedAtAsc("8613800000010").get(0).getFirstAiReplyAt();
        history.recordAiReply("8613800000010", "ai-2");
        history.recordManualReply("8613800000010", "manual");

        assertThat(conversations.findByCustomerPhoneOrderByCreatedAtAsc("8613800000010")).singleElement().satisfies(conversation -> {
            assertThat(conversation.getFirstCustomerMessageAt()).isNotNull();
            assertThat(conversation.getFirstAiReplyAt()).isEqualTo(firstAiAt);
            assertThat(conversation.getFirstAgentReplyAt()).isNotNull();
        });
        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc("8613800000010")).hasSize(5);
    }

    @Test
    void blankNicknameIsIgnoredAndBlankCustomerIdIsRejected() {
        history.recordCustomerMessage("8613800000011", "hello");
        history.setCustomerNickname("8613800000011", "Alice");
        history.setCustomerNickname("8613800000011", "   ");
        assertThat(resources.findByCustomerPhone("8613800000011")).get()
                .extracting(resource -> resource.getCustomerName()).isEqualTo("Alice");

        assertThatThrownBy(() -> history.recordCustomerMessage(" ", "invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    void pagingNormalizesInvalidParametersAndReturnsEmptyOutOfRangePage() {
        for (int i = 0; i < 3; i++) history.recordCustomerMessage("8613800000012", "m" + i);
        var normalized = history.listMessagesPaged("8613800000012", -1, 1000, false);
        assertThat(normalized.getPage()).isEqualTo(1);
        assertThat(normalized.getSize()).isEqualTo(50);
        assertThat(normalized.getItems()).extracting(ChatMessageRecord::getMessage).containsExactly("m0", "m1", "m2");
        assertThat(history.listMessagesPaged("8613800000012", 99, 2, false).getItems()).isEmpty();
        assertThat(history.listMessages("missing-customer")).isEmpty();
        assertThat(history.lastCustomerMessageTime("missing-customer")).isEmpty();
    }

    @Test
    void snapshotAndLegacyReplacePreserveOrderingAndMapUnknownSenderToSystem() {
        Instant first = Instant.now().minusSeconds(2);
        Instant second = Instant.now().minusSeconds(1);
        history.replaceAll(Map.of("8613800000020", List.of(
                ChatMessageRecord.builder().customerId("8613800000020").sender("unexpected").message("second").timestamp(second).build(),
                ChatMessageRecord.builder().customerId("8613800000020").sender("customer").message("first").timestamp(first).build())));

        assertThat(history.listMessages("8613800000020")).extracting(ChatMessageRecord::getMessage)
                .containsExactly("first", "second");
        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc("8613800000020").get(1).getSenderType())
                .isEqualTo(SenderType.SYSTEM);
        assertThat(history.snapshot()).containsKey("8613800000020");
        history.replaceAll(null);
        assertThat(history.listMessages("8613800000020")).hasSize(2);
    }

    @Test
    void thirtyDayInactivityClosesOldConversation() {
        Instant old = Instant.now().minus(31, ChronoUnit.DAYS);
        history.replaceAll(Map.of("8613800000002", List.of(ChatMessageRecord.builder()
                .customerId("8613800000002").sender("customer").message("old").timestamp(old).build())));
        history.recordCustomerMessage("8613800000002", "new");

        assertThat(conversations.findByCustomerPhoneOrderByCreatedAtAsc("8613800000002")).hasSize(2);
        assertThat(conversations.findByCustomerPhoneOrderByCreatedAtAsc("8613800000002").get(0).getStatus())
                .isEqualTo(ConversationStatus.CLOSED);
    }

    @Test
    void externalMessageIsIdempotentAndStatusDoesNotRegress() {
        messagePersistence.recordIncoming("8613800000003", "account", "wamid.1", "text", "once",
                null, null, null, "{}", Instant.now());
        messagePersistence.recordIncoming("8613800000003", "account", "wamid.1", "text", "twice",
                null, null, null, "{}", Instant.now());
        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc("8613800000003")).hasSize(1);

        messagePersistence.updateDeliveryStatus("wamid.1", SentStatus.READ, Instant.now(), null);
        messagePersistence.updateDeliveryStatus("wamid.1", SentStatus.SENT, Instant.now(), null);
        assertThat(messages.findByExternalMessageId("wamid.1")).get()
                .extracting(message -> message.getSentStatus()).isEqualTo(SentStatus.READ);

        long outgoing = messagePersistence.createOutgoing("8613800000003", "account", SenderType.AI, null,
                null, MessageType.TEXT, "reply", null, null, null);
        assertThat(messages.findById(outgoing)).get().extracting(m -> m.getSentStatus()).isEqualTo(SentStatus.PENDING);
        messagePersistence.markOutgoingSent(outgoing, "wamid.out", Instant.now());
        messagePersistence.updateDeliveryStatus("wamid.out", SentStatus.DELIVERED, Instant.now(), null);
        assertThat(messages.findById(outgoing)).get().extracting(m -> m.getSentStatus()).isEqualTo(SentStatus.DELIVERED);
    }

    @Test
    void incomingMessagesWithoutExternalIdRemainValidAndUnknownTypeBecomesSystem() {
        messagePersistence.recordIncoming("8613800000013", "account", null, "unsupported-type", "first",
                null, null, null, "{}", Instant.now());
        messagePersistence.recordIncoming("8613800000013", "account", null, "text", "second",
                null, null, null, "{}", Instant.now());

        assertThat(messages.findByCustomerPhoneOrderByCreatedAtAscIdAsc("8613800000013"))
                .hasSize(2).first().extracting(message -> message.getMessageType()).isEqualTo(MessageType.SYSTEM);
    }

    @Test
    void concurrentDuplicateExternalMessageLeavesExactlyOneCommittedRecord() throws Exception {
        history.recordCustomerMessage("8613800000021", "seed resource");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> insert = () -> {
            start.await();
            try {
                messagePersistence.recordIncoming("8613800000021", "account", "wamid.concurrent", "text", "same",
                        null, null, null, "{}", Instant.now());
            } catch (DataIntegrityViolationException expectedDuplicate) {
                // Unique-key loser is an expected outcome of concurrent webhook delivery.
            }
            return null;
        };
        try {
            Future<Void> first = executor.submit(insert);
            Future<Void> second = executor.submit(insert);
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(messages.findAll().stream()
                .filter(message -> "wamid.concurrent".equals(message.getExternalMessageId()))).hasSize(1);
    }

    @Test
    void deliveryFailureIsTerminalExceptReadAndFailureReasonIsTruncated() {
        long failed = messagePersistence.createOutgoing("8613800000014", "account", SenderType.AGENT, "agent-a",
                "TMK", MessageType.TEXT, "will fail", null, null, null);
        messagePersistence.markOutgoingFailed(failed, "x".repeat(1200), Instant.now());
        messagePersistence.updateDeliveryStatus("unknown-id", SentStatus.READ, Instant.now(), null);
        assertThat(messages.findById(failed)).get().satisfies(message -> {
            assertThat(message.getSentStatus()).isEqualTo(SentStatus.FAILED);
            assertThat(message.getFailureReason()).hasSize(1000);
            assertThat(message.getFailedAt()).isNotNull();
        });

        long read = messagePersistence.createOutgoing("8613800000014", "account", SenderType.AGENT, "agent-a",
                "TMK", MessageType.TEXT, "read", null, null, null);
        messagePersistence.markOutgoingSent(read, "wamid.read", Instant.now());
        messagePersistence.updateDeliveryStatus("wamid.read", SentStatus.READ, Instant.now(), null);
        messagePersistence.updateDeliveryStatus("wamid.read", SentStatus.FAILED, Instant.now(), "late failure");
        assertThat(messages.findById(read)).get().extracting(message -> message.getSentStatus()).isEqualTo(SentStatus.READ);
        messagePersistence.markOutgoingSent(Long.MAX_VALUE, "missing", Instant.now());
        messagePersistence.markOutgoingFailed(Long.MAX_VALUE, "missing", Instant.now());
    }

    @Test
    void outgoingMediaPersistsAuditAndTransportMetadata() {
        long id = messagePersistence.createOutgoing("8613800000022", "business-account", SenderType.MANAGER,
                "manager-1", "MANAGER", MessageType.DOCUMENT, "[document] contract.pdf",
                "media-22", "https://media.example/22", "application/pdf");

        assertThat(messages.findById(id)).get().satisfies(message -> {
            assertThat(message.getSenderType()).isEqualTo(SenderType.MANAGER);
            assertThat(message.getSenderId()).isEqualTo("manager-1");
            assertThat(message.getOperatorRole()).isEqualTo("MANAGER");
            assertThat(message.getMessageType()).isEqualTo(MessageType.DOCUMENT);
            assertThat(message.getMediaId()).isEqualTo("media-22");
            assertThat(message.getMediaUrl()).isEqualTo("https://media.example/22");
            assertThat(message.getMimeType()).isEqualTo("application/pdf");
            assertThat(message.getSentStatus()).isEqualTo(SentStatus.PENDING);
        });
    }

    @Test
    void databaseRejectsTwoServingAssignmentsForSameResource() {
        history.recordCustomerMessage("8613800000015", "hello");
        var resource = resources.findByCustomerPhone("8613800000015").orElseThrow();
        var conversation = conversations.findByCustomerPhoneOrderByCreatedAtAsc("8613800000015").get(0);
        assignments.saveAndFlush(assignment(resource.getId(), conversation.getId(), "8613800000015", "agent-a"));
        assertThatThrownBy(() -> assignments.saveAndFlush(
                assignment(resource.getId(), conversation.getId(), "8613800000015", "agent-b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentAssignmentCreatesOnlyOneServingRecord() throws Exception {
        history.recordCustomerMessage("8613800000004", "assign me");
        dispatch.markOnline("agent-a");
        dispatch.markOnline("agent-b");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<String> task = () -> { start.await(); return dispatch.assignIfAbsent("8613800000004").orElseThrow(); };
            Future<String> first = executor.submit(task);
            Future<String> second = executor.submit(task);
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertThat(assignments.findByStatus(AssignmentStatus.SERVING)).hasSize(1);
        assertThat(dispatch.assignmentsSnapshot()).containsKey("8613800000004");
        String original = dispatch.getAssignedAgent("8613800000004").orElseThrow();
        String target = original.equals("agent-a") ? "agent-b" : "agent-a";
        assertThat(dispatch.transferCustomer("8613800000004", target, original)).contains(target);
        assertThat(assignments.findByStatus(AssignmentStatus.TRANSFERRED)).hasSize(1);
        assertThat(assignments.findByStatus(AssignmentStatus.SERVING)).singleElement()
                .extracting(a -> a.getAgentId()).isEqualTo(target);
        dispatch.unassignCustomer("8613800000004");
        assertThat(assignments.findByStatus(AssignmentStatus.SERVING)).isEmpty();
    }

    @Test
    void assignmentNegativeAndCapacityScenariosDoNotCreateInvalidBindings() {
        history.recordCustomerMessage("8613800000016", "pending");
        assertThat(dispatch.assignIfAbsent("8613800000016")).isEmpty();
        assertThat(resources.findByCustomerPhone("8613800000016")).get()
                .extracting(resource -> resource.getResourceStatus()).isEqualTo(ResourceStatus.PENDING_ASSIGNMENT);
        assertThat(assignments.findAll()).isEmpty();
        assertThatThrownBy(() -> dispatch.assignIfAbsent(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerPhone");

        dispatch.markOnline("agent-capacity");
        dispatch.setAgentProfile("agent-capacity", "中级", 1, 1);
        assertThat(dispatch.assignIfAbsent("8613800000016")).contains("agent-capacity");
        history.recordCustomerMessage("8613800000017", "second customer");
        assertThat(dispatch.assignIfAbsent("8613800000017")).isEmpty();
        assertThat(dispatch.transferCustomer("missing", "agent-capacity", "manager")).isEmpty();
        assertThat(dispatch.transferCustomer("8613800000016", " ", "manager")).isEmpty();
        dispatch.unassignCustomer("missing");
    }

    @Test
    void crmReplacementStateCannotOverwriteDatabaseAssignments() {
        history.recordCustomerMessage("8613800000018", "hello");
        dispatch.markOnline("local-agent");
        assertThat(dispatch.assignIfAbsent("8613800000018")).contains("local-agent");

        dispatch.replaceState(java.util.Set.of("crm-online"), Map.of("8613800000018", "crm-agent"));
        assertThat(dispatch.onlineAgentsSnapshot()).containsExactly("crm-online");
        assertThat(dispatch.getAssignedAgent("8613800000018")).contains("local-agent");
    }

    @Test
    void timeoutScanWarnsThenClosesPersistentAssignment() {
        history.recordCustomerMessage("8613800000019", "hello");
        dispatch.markOnline("agent-timeout");
        dispatch.assignIfAbsent("8613800000019");
        var resource = resources.findByCustomerPhone("8613800000019").orElseThrow();
        resource.setLastCustomerMessageAt(Instant.now().minus(6, ChronoUnit.MINUTES));
        resources.saveAndFlush(resource);
        assertThat(dispatch.scanTimeouts(5, 10).overdueWarnCustomers()).contains("8613800000019");

        resource = resources.findById(resource.getId()).orElseThrow();
        resource.setLastCustomerMessageAt(Instant.now().minus(11, ChronoUnit.MINUTES));
        resources.saveAndFlush(resource);
        assertThat(dispatch.scanTimeouts(5, 10).reclaimedCustomers()).contains("8613800000019");
        assertThat(dispatch.getAssignedAgent("8613800000019")).isEmpty();
        assertThat(assignments.findByStatus(AssignmentStatus.CLOSED)).hasSize(1);
    }

    private com.example.aitmk.model.entity.AssignmentRecordEntity assignment(
            Long resourceId, Long conversationId, String phone, String agent) {
        var assignment = new com.example.aitmk.model.entity.AssignmentRecordEntity();
        assignment.setResourceId(resourceId);
        assignment.setConversationId(conversationId);
        assignment.setCustomerPhone(phone);
        assignment.setAgentId(agent);
        assignment.setAssignedBy("test");
        assignment.setAssignType(AssignType.AUTO);
        return assignment;
    }
}
