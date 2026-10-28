package com.alfagift.finledger.infrastructure.kafka;

import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Konsumen event poin loyalty dari topik {@code loyalty-points-awarded}.
 * <p>
 * Dalam produksi, konsumen ini akan trigger push notification / email.
 * Untuk development, simulasi dengan log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoyaltyPointsConsumer {

    @KafkaListener(topics = "${kafka.topic.points-awarded:loyalty-points-awarded}",
            groupId = "${spring.kafka.consumer.group-id:finledger-engine}")
    public void consumePointsAwarded(PointsAwardedEvent event, Acknowledgment acknowledgment) {
        log.info("""
                ============================================================================
                [KAFKA CONSUMER] PointsAwardedEvent diterima
                - transactionId : {}
                - ownerId       : {}
                - currency      : {}
                - amount        : {}
                - awardedAt     : {}
                - description   : {}
                ============================================================================
                """,
                event.getTransactionId(),
                event.getOwnerId(),
                event.getCurrency(),
                event.getAmount(),
                event.getAwardedAt(),
                event.getDescription());

        // Simulasi pengiriman push notification / email ke pelanggan
        log.info("Simulasi: Push notification dikirim ke {} — Anda menerima {} {}", 
                event.getOwnerId(), event.getAmount(), event.getCurrency());

        acknowledgment.acknowledge();
    }
}