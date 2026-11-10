package com.alfagift.finledger.infrastructure.outbox;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.OutboxStatus;
import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import com.alfagift.finledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link OutboxService}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("enqueue harus menyimpan payload JSON dengan status PENDING")
    void enqueue_shouldPersistPendingEvent() {
        OutboxService service = new OutboxService(outboxEventRepository, new ObjectMapper());
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PointsAwardedEvent payload = PointsAwardedEvent.builder()
                .transactionId("tx-9")
                .ownerId("cust-001")
                .currency(Currency.A_POIN)
                .amount(new BigDecimal("1000"))
                .build();

        service.enqueue("JOURNAL_TRANSACTION", "tx-9", "POINTS_AWARDED", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAggregateId()).isEqualTo("tx-9");
        assertThat(saved.getPayload()).contains("\"transactionId\":\"tx-9\"");
    }
}
