package com.alfagift.finledger.infrastructure.kafka;

import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

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
     * Publish event poin diberikan kepada pelanggan.
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
}