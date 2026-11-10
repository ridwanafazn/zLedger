package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.domain.enums.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository untuk {@link OutboxEvent}.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop50ByStatusOrderByCreatedAtAsc(OutboxStatus status);

    Page<OutboxEvent> findByStatus(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);
}
