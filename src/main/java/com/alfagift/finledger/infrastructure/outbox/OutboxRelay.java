package com.alfagift.finledger.infrastructure.outbox;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.domain.enums.OutboxStatus;
import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import com.alfagift.finledger.infrastructure.kafka.LoyaltyPointsProducer;
import com.alfagift.finledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Relay yang mempublikasikan event {@code PENDING} ke Kafka secara terjadwal.
 * <p>
 * Setiap event dicoba sampai {@code max-attempts}; kegagalan dicatat pada
 * {@code lastError} dan status menjadi {@code FAILED} setelah batas tercapai.
 * Pengiriman bersifat <b>at-least-once</b>; consumer harus idempoten.
 */
@Component
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final LoyaltyPointsProducer loyaltyPointsProducer;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       LoyaltyPointsProducer loyaltyPointsProducer,
                       ObjectMapper objectMapper,
                       @Value("${app.outbox.max-attempts:5}") int maxAttempts) {
        this.outboxEventRepository = outboxEventRepository;
        this.loyaltyPointsProducer = loyaltyPointsProducer;
        this.objectMapper = objectMapper;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);

        if (pending.isEmpty()) {
            return;
        }

        log.info("Outbox relay memproses {} event", pending.size());

        for (OutboxEvent event : pending) {
            try {
                PointsAwardedEvent payload = objectMapper.readValue(event.getPayload(), PointsAwardedEvent.class);
                loyaltyPointsProducer.sendPointsAwardedAndWait(payload);

                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                event.setLastError(null);
                outboxEventRepository.save(event);
            } catch (Exception ex) {
                int attempts = event.getAttempts() + 1;
                event.setAttempts(attempts);
                event.setLastError(truncate(ex.getMessage()));
                event.setStatus(attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING);
                outboxEventRepository.save(event);
                log.warn("Gagal publikasi outbox {} (attempt {}): {}", event.getId(), attempts, ex.getMessage());
            }
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
