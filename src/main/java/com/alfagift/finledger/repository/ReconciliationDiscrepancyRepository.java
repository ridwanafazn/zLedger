package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.ReconciliationDiscrepancy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository temuan rekonsiliasi.
 */
public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancy, UUID> {

    List<ReconciliationDiscrepancy> findByRunIdOrderByDiscrepancyTypeAsc(UUID runId);

    long countByRunId(UUID runId);
}
