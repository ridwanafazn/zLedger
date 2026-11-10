package com.alfagift.finledger.infrastructure.kafka;

import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Produsen event poin loyalty. Mengirim {@link PointsAwardedEvent} ke topik
 * {@code loyalty-points-awarded} selepas transaksi yang melibatkan A_Poin berjaya.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyPointsProducer {

    private final KafkaTemplate<String, PointsAwardedEvent> kafkaTemplate;

    @Value("${kafka.topic.points-awarded:loyalty-points-awarded}")
    private String topicPointsAwarded;

    /**
     * Publish event poin diberikan kepada pelanggan (fire-and-forget).
     */
    public void sendPointsAwarded(PointsAwardedEvent event) {
        kafkaTemplate.send(topicPointsAwarded, event.getTransactionId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Gagal mengirim Kafka event untuk transaksi {}: {}",
                                event.getTransactionId(), ex.getMessage(), ex);
                    } else {
                        log.info("Kafka event terkirim: transactionId={}, topik={}, offset={}",
                                event.getTransactionId(), topicPointsAwarded,
                                result.getRecordMetadata().offset());
                    }
                });
    }

    /**
     * Publish event dan tunggu acknowledgment broker.
     * <p>
     * Dipakai oleh Outbox relay agar event baru ditandai {@code PUBLISHED}
     * setelah broker benar-benar menerima pesan.
     *
     * @throws IllegalStateException bila pengiriman gagal/timeout
     */
    public void sendPointsAwardedAndWait(PointsAwardedEvent event) {
        try {
            kafkaTemplate.send(topicPointsAwarded, event.getTransactionId(), event)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Gagal mengirim Kafka event untuk transaksi " + event.getTransactionId(), ex);
        }
    }
}