package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository untuk entiti {@link LedgerEntry}.
 */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    /**
     * Semua entri ledger bagi suatu transaksi.
     */
    List<LedgerEntry> findByJournalTransactionId(UUID transactionId);

    /**
     * Semua entri ledger bagi suatu akaun (mutasi akaun), terbaru dulu.
     */
    List<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /**
     * Mutasi akaun dengan pagination.
     */
    org.springframework.data.domain.Page<LedgerEntry> findByAccountId(
            UUID accountId, org.springframework.data.domain.Pageable pageable);
}