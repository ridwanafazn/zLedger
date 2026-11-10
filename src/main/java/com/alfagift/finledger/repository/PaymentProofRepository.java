package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.PaymentProof;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository metadata bukti bayar.
 */
public interface PaymentProofRepository extends JpaRepository<PaymentProof, UUID> {

    List<PaymentProof> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    boolean existsByTransactionIdAndChecksumSha256(UUID transactionId, String checksumSha256);
}
