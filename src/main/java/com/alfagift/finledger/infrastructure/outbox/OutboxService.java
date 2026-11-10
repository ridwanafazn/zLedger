package com.alfagift.finledger.infrastructure.outbox;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Menulis event ke tabel outbox <b>dalam transaksi bisnis yang sama</b>.
 * <p>
 * Propagasi {@code MANDATORY} memastikan {@code enqueue} tidak pernah dipanggil
 * di luar transaksi — jika dipanggil tanpa transaksi, akan melempar exception
 * sehingga atomicity ledger + outbox terjaga.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent enqueue(String aggregateType, String aggregateId, String eventType, Object payload) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(serialize(payload))
                .build();

        OutboxEvent saved = outboxEventRepository.save(event);
        log.debug("Event outbox didaftarkan: type={}, aggregateId={}", eventType, aggregateId);
        return saved;
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Gagal menyerialkan payload outbox", ex);
        }
    }
}
