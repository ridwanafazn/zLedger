package com.alfagift.finledger.infrastructure.outbox;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.domain.enums.OutboxStatus;
import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import com.alfagift.finledger.infrastructure.kafka.LoyaltyPointsProducer;
import com.alfagift.finledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link OutboxRelay}.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private LoyaltyPointsProducer loyaltyPointsProducer;

    private OutboxRelay relay;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxEvent pendingEvent;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxEventRepository, loyaltyPointsProducer, objectMapper, 3);

        pendingEvent = OutboxEvent.builder()
                .aggregateType("JOURNAL_TRANSACTION")
                .aggregateId("tx-1")
                .eventType("POINTS_AWARDED")
                .payload("{\"transactionId\":\"tx-1\",\"ownerId\":\"cust-001\",\"currency\":\"A_POIN\",\"amount\":1000}")
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .build();
        pendingEvent.setId(java.util.UUID.randomUUID());
    }

    @Test
    @DisplayName("Event yang berhasil dikirim harus ditandai PUBLISHED")
    void successfulPublish_shouldMarkPublished() {
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pendingEvent));
        when(outboxEventRepository.save(any(OutboxEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        relay.publishPendingEvents();

        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(pendingEvent.getPublishedAt()).isNotNull();
        verify(loyaltyPointsProducer).sendPointsAwardedAndWait(any(PointsAwardedEvent.class));
    }

    @Test
    @DisplayName("Kegagalan kirim harus menaikkan attempts dan menyimpan lastError")
    void failedPublish_shouldRecordAttempt() {
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pendingEvent));
        doThrow(new IllegalStateException("Kafka down"))
                .when(loyaltyPointsProducer).sendPointsAwardedAndWait(any());

        relay.publishPendingEvents();

        assertThat(pendingEvent.getAttempts()).isEqualTo(1);
        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(pendingEvent.getLastError()).contains("Kafka down");
    }

    @Test
    @DisplayName("Setelah batas percobaan tercapai status menjadi FAILED")
    void exhaustedAttempts_shouldMarkFailed() {
        pendingEvent.setAttempts(2); // max attempts = 3, satu kegagalan lagi → FAILED
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(pendingEvent));
        doThrow(new IllegalStateException("Kafka down"))
                .when(loyaltyPointsProducer).sendPointsAwardedAndWait(any());

        relay.publishPendingEvents();

        assertThat(pendingEvent.getAttempts()).isEqualTo(3);
        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @DisplayName("Tidak ada event PENDING → tidak ada interaksi dengan producer")
    void noPendingEvents_shouldDoNothing() {
        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of());

        relay.publishPendingEvents();

        verify(loyaltyPointsProducer, org.mockito.Mockito.never())
                .sendPointsAwardedAndWait(any());
    }

    @Test
    @DisplayName("Payload rusak harus dianggap gagal, bukan melempar ke pemanggil")
    void malformedPayload_shouldNotCrashRelay() {
        OutboxEvent broken = OutboxEvent.builder()
                .aggregateType("JOURNAL_TRANSACTION")
                .aggregateId("tx-2")
                .eventType("POINTS_AWARDED")
                .payload("bukan-json")
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .build();
        broken.setId(java.util.UUID.randomUUID());

        when(outboxEventRepository.findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING))
                .thenReturn(List.of(broken));

        relay.publishPendingEvents();

        assertThat(broken.getAttempts()).isEqualTo(1);
        assertThat(broken.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }
}
