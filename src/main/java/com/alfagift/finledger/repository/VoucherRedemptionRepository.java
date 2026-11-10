package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.VoucherRedemption;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository riwayat redeem voucher.
 */
public interface VoucherRedemptionRepository extends JpaRepository<VoucherRedemption, UUID> {

    boolean existsByVoucherIdAndOwnerId(UUID voucherId, String ownerId);

    Page<VoucherRedemption> findByOwnerId(String ownerId, Pageable pageable);
}
