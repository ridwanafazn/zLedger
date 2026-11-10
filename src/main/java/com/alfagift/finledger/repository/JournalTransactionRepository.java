package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.enums.ReferenceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
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
}