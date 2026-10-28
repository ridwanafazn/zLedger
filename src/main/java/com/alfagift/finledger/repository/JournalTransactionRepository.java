package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.JournalTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository untuk entiti {@link JournalTransaction}.
 */
public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, UUID> {

    /**
     * Mencari transaksi berdasarkan kunci idempotency.
     * Digunakan untuk mengesan permintaan duplikat.
     */
    @Query("SELECT jt FROM JournalTransaction jt WHERE jt.idempotencyKey = :key")
    Optional<JournalTransaction> findByIdempotencyKey(String key);

    boolean existsByIdempotencyKey(String idempotencyKey);
}