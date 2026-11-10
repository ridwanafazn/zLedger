package com.alfagift.finledger.service;

import com.alfagift.finledger.domain.enums.VoucherStatus;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.VoucherCreateRequest;
import com.alfagift.finledger.dto.VoucherRedemptionResponse;
import com.alfagift.finledger.dto.VoucherResponse;

import java.util.UUID;

/**
 * Use case katalog voucher dan redeem flash sale.
 */
public interface VoucherService {

    VoucherResponse create(VoucherCreateRequest request);

    VoucherResponse get(UUID voucherId);

    PageResponse<VoucherResponse> search(String status, Boolean activeOnly, int page, int size);

    VoucherResponse updateStatus(UUID voucherId, VoucherStatus status);

    /**
     * Redeem voucher secara atomik: kuota dikurangi di bawah pessimistic lock,
     * poin dipotong lewat jurnal ledger double-entry.
     *
     * @param voucherId ID voucher
     * @param ownerId   pemilik akun poin
     * @return detail redeem
     */
    VoucherRedemptionResponse redeem(UUID voucherId, String ownerId);
}
