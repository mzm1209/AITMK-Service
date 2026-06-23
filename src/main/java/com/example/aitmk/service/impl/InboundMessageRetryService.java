package com.example.aitmk.service.impl;

import com.example.aitmk.service.MessagePersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j @Service @RequiredArgsConstructor
public class InboundMessageRetryService {
    private static final int MAX_ATTEMPTS = 3;
    private final MessagePersistenceService persistence;

    public MessagePersistenceService.IncomingResult persist(String phone, String accountId, String externalId,
            String type, String content, String mediaId, String mediaUrl, String mimeType, String rawPayload, Instant receivedAt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // recordIncoming 是独立 Bean 的 REQUIRES_NEW 方法，每次循环均开启新事务。
                return persistence.recordIncoming(phone, accountId, externalId, type, content, mediaId, mediaUrl,
                        mimeType, rawPayload, receivedAt);
            } catch (DataIntegrityViolationException ex) {
                if (persistence.existsExternalMessage(externalId)) return MessagePersistenceService.IncomingResult.DUPLICATE;
                last = ex;
            } catch (RuntimeException ex) {
                if (!retryable(ex)) throw ex;
                last = ex;
            }
            if (attempt < MAX_ATTEMPTS) backoff(attempt);
        }
        log.error("Inbound message persistence exhausted. externalMessageId={}, customer={}, attempts={}, errorType={}",
                safeExternalId(externalId), maskPhone(phone), MAX_ATTEMPTS, last == null ? "unknown" : last.getClass().getSimpleName());
        throw new InboundMessagePersistenceException("入站消息持久化重试耗尽", last);
    }

    private boolean retryable(RuntimeException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof ObjectOptimisticLockingFailureException
                    || current instanceof CannotAcquireLockException
                    || current instanceof PessimisticLockingFailureException) return true;
        }
        return hasDeadlockCause(ex);
    }

    private boolean hasDeadlockCause(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String name = current.getClass().getSimpleName().toLowerCase();
            if (name.contains("deadlock")) return true;
        }
        return false;
    }

    private void backoff(int attempt) {
        try { Thread.sleep(25L << (attempt - 1)); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new InboundMessagePersistenceException("入站消息重试被中断", ex); }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
    private String safeExternalId(String id) { return id == null ? "" : id.substring(0, Math.min(id.length(), 128)); }

    public static class InboundMessagePersistenceException extends RuntimeException {
        public InboundMessagePersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
