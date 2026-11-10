package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository untuk entiti {@link JournalTransaction}.
 */
public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID>,
        JpaSpecificationExecutor<JournalTransaction> {

    /**
     * Mencari transaksi berdasarkan kunci idempotency.
     * Digunakan untuk mengesan permintaan duplikat.
     */
    @Query("SELECT jt FROM JournalTransaction jt WHERE jt.idempotencyKey = :key")
    Optional<JournalTransaction> findByIdempotencyKey(String key);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * Daftar transaksi berdasarkan jenis referensi (paginated).
     */
    Page<JournalTransaction> findByReferenceType(ReferenceType referenceType, Pageable pageable);

    /**
     * Menentukan apakah sebuah transaksi sudah pernah dibalik.
     */
    boolean existsByReversalOfId(UUID originalTransactionId);

    /**
     * Jurnal yang jumlah DEBIT-nya tidak sama dengan jumlah CREDIT.
     * <p>
     * Rekonsiliasi memakai query ini untuk mendeteksi kerusakan data ledger,
     * bukan hanya selisih cached balance.
     */
    @Query("""
            select new com.alfagift.finledger.repository.JournalBalanceCheck(
                       jt.id,
                       jt.referenceType,
                       jt.description,
                       sum(case when le.entryType = :debit then le.amount else -le.amount end))
              from JournalTransaction jt
              join jt.ledgerEntries le
             group by jt.id, jt.referenceType, jt.description
            having sum(case when le.entryType = :debit then le.amount else -le.amount end) <> 0
            """)
    List<JournalBalanceCheck> findUnbalancedJournals(@Param("debit") EntryType debit);

    /**
     * Id jurnal yang punya kurang dari 2 entri (melanggar aturan double-entry).
     */
    @Query("select jt.id from JournalTransaction jt where size(jt.ledgerEntries) < 2")
    List<UUID> findJournalIdsWithInsufficientEntries();
}
