package com.example.aitmk.service.impl;

import com.example.aitmk.service.MessagePersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboundMessageRetryServiceTest {
    private final MessagePersistenceService persistence=mock(MessagePersistenceService.class);
    private final InboundMessageRetryService retry=new InboundMessageRetryService(persistence);

    @Test void firstLockConflictRetriesInANewInvocationAndSucceeds() {
        when(persistence.recordIncoming(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),anyString(),any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("conversation",1L))
                .thenReturn(MessagePersistenceService.IncomingResult.CREATED);
        assertThat(call()).isEqualTo(MessagePersistenceService.IncomingResult.CREATED);
        verify(persistence,times(2)).recordIncoming(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),anyString(),any());
    }

    @Test void confirmedUniqueConflictIsDuplicateButOtherIntegrityErrorsAreNotSwallowed() {
        when(persistence.recordIncoming(anyString(),anyString(),eq("wamid.retry"),anyString(),anyString(),any(),any(),any(),anyString(),any()))
                .thenThrow(new DataIntegrityViolationException("unique"));
        when(persistence.existsExternalMessage("wamid.retry")).thenReturn(true);
        assertThat(call()).isEqualTo(MessagePersistenceService.IncomingResult.DUPLICATE);

        reset(persistence);
        when(persistence.recordIncoming(anyString(),anyString(),eq("wamid.retry"),anyString(),anyString(),any(),any(),any(),anyString(),any()))
                .thenThrow(new DataIntegrityViolationException("other constraint"));
        when(persistence.existsExternalMessage("wamid.retry")).thenReturn(false);
        assertThatThrownBy(this::call).isInstanceOf(InboundMessageRetryService.InboundMessagePersistenceException.class);
        verify(persistence,times(3)).recordIncoming(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),anyString(),any());
    }

    @Test void retryExhaustionProducesExplicitFailure() {
        when(persistence.recordIncoming(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),anyString(),any()))
                .thenThrow(new CannotAcquireLockException("busy"));
        assertThatThrownBy(this::call).isInstanceOf(InboundMessageRetryService.InboundMessagePersistenceException.class)
                .hasMessageContaining("重试耗尽");
        verify(persistence,times(3)).recordIncoming(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),any(),anyString(),any());
    }

    private MessagePersistenceService.IncomingResult call(){return retry.persist("8613800000999","business","wamid.retry","text","same",null,null,null,"{}", Instant.now());}
}
