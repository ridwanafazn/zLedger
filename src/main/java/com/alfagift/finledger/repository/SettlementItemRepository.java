package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.SettlementItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository rincian batch settlement.
 */
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {

    List<SettlementItem> findBySettlementIdOrderByOccurredAtAsc(UUID settlementId);

    long countBySettlementId(UUID settlementId);
}
