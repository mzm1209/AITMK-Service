package com.example.aitmk.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

public class CrmHistoryMigrationReport {
    private final Instant startedAt = Instant.now();
    long assignmentRowsSeen;
    long assignmentRowsValid;
    long assignmentRowsInvalid;
    long assignmentServingRows;
    long assignmentClosedRows;
    long assignmentReplyableYes;
    long assignmentReplyableNo;
    long messageRowsSeen;
    long messageRowsValid;
    long messageRowsInvalid;
    long senderCustomer;
    long senderAgent;
    long senderAi;
    long senderSystem;
    long uniqueCustomers;
    long resourcesCreated;
    long resourcesReused;
    long conversationsCreated;
    long conversationsReused;
    long assignmentsInserted;
    long assignmentsSkippedDuplicate;
    long assignmentsConflict;
    long messagesInserted;
    long messagesSkippedDuplicate;
    long unreadStatesCreated;
    long failedCustomers;
    long failedRows;
    private final Map<String, Long> failures = new TreeMap<>();

    void fail(String reason) {
        failedRows++;
        failures.merge(reason == null || reason.isBlank() ? "UNKNOWN" : reason, 1L, Long::sum);
    }

    void invalidAssignment(String reason) {
        assignmentRowsInvalid++;
        fail(reason);
    }

    void invalidMessage(String reason) {
        messageRowsInvalid++;
        fail(reason);
    }

    long elapsedSeconds() {
        return Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
    }

    String summary(boolean dryRun) {
        return """
                [CRM-MIGRATION] completed dryRun=%s
                  elapsedSeconds=%d

                  crmAssignmentsSeen=%d
                  crmAssignmentsValid=%d
                  crmServingAssignments=%d
                  crmClosedAssignments=%d
                  crmAssignmentInvalid=%d
                  crmAssignmentReplyableYes=%d
                  crmAssignmentReplyableNo=%d

                  crmMessagesSeen=%d
                  crmMessagesValid=%d
                  crmMessageInvalid=%d
                  senderCustomer=%d
                  senderAgent=%d
                  senderAi=%d
                  senderSystem=%d

                  uniqueCustomers=%d
                  resourcesCreated=%d
                  resourcesReused=%d
                  conversationsCreated=%d
                  conversationsReused=%d
                  assignmentsInserted=%d
                  assignmentsSkippedDuplicate=%d
                  assignmentsConflict=%d
                  messagesInserted=%d
                  messagesSkippedDuplicate=%d
                  unreadStatesCreated=%d

                  failedCustomers=%d
                  failedRows=%d
                  failures=%s
                """.formatted(dryRun, elapsedSeconds(), assignmentRowsSeen, assignmentRowsValid,
                assignmentServingRows, assignmentClosedRows, assignmentRowsInvalid, assignmentReplyableYes,
                assignmentReplyableNo, messageRowsSeen, messageRowsValid, messageRowsInvalid, senderCustomer,
                senderAgent, senderAi, senderSystem, uniqueCustomers, resourcesCreated, resourcesReused,
                conversationsCreated, conversationsReused, assignmentsInserted, assignmentsSkippedDuplicate,
                assignmentsConflict, messagesInserted, messagesSkippedDuplicate, unreadStatesCreated,
                failedCustomers, failedRows, failures);
    }
}
