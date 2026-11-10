package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.MerchantSettlement;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.SettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository batch settlement merchant.
 * <p>
 * Payout memakai pessimistic lock agar dua permintaan payout paralel tidak
 * bisa membayar batch yang sama dua kali.
 */
public interface MerchantSettlementRepository
        extends JpaRepository<MerchantSettlement, UUID>, JpaSpecificationExecutor<MerchantSettlement> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MerchantSettlement s where s.id = :id")
    Optional<MerchantSettlement> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<MerchantSettlement> findByIdempotencyKey(String idempotencyKey);

    /**
     * Mendeteksi periode yang bertumpuk untuk merchant + mata uang yang sama.
     * Batch CANCELLED diabaikan sehingga periodenya boleh dipakai ulang.
     */
    @Query("""
            select count(s) > 0
              from MerchantSettlement s
             where s.merchantId = :merchantId
               and s.currency = :currency
               and s.status <> :cancelled
               and s.periodStart < :periodEnd
               and s.periodEnd > :periodStart
            """)
    boolean existsOverlappingPeriod(@Param("merchantId") String merchantId,
                                    @Param("currency") Currency currency,
                                    @Param("periodStart") LocalDateTime periodStart,
                                    @Param("periodEnd") LocalDateTime periodEnd,
                                    @Param("cancelled") SettlementStatus cancelled);
}
