package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Intisari bisnis FinLedger Engine.
 * <p>
 * Mesej:
 * <ul>
 *   <li>Semua operasi transaksi diwakili oleh {@link Transactional}</li>
 *   <li>Double-entry dijamin: jumlah DEBIT = jumlah CREDIT per transaksi</li>
 *   <li>Kunci pessimistik digunakan pada akaun semasa transaksi</li>
 *   <li>Tidak ada DELETE/UPDATE pada entri ledger; reversal via compensating entry</li>
 * </ul>
 */
public interface LedgerService {

    /**
     * Proses pembayaran multi-tender (campuran IDR + A_Poin).
     *
     * @param request permintaan checkout dari client
     * @return respons checkout yang mengandungi ID transaksi dan balans sisa
     * @throws InsufficientBalanceException jika balans pelanggan tidak mencukupi
     */
    @Transactional
    CheckoutResponse processMultiTenderPayment(CheckoutRequest request);

    /**
     * Proses tebasan flash sale (voucher terbatas dengan A_Poin).
     *
     * @param ownerId   ID pelanggan
     * @param voucherId ID voucher
     * @param points    Jumlah A_Poin yang ditebus
     * @param idempotencyKey kunci idempotency
     * @return respons tebasan
     * @throws InsufficientBalanceException jika poin tidak mencukupi
     */
    @Transactional
    CheckoutResponse redeemFlashSale(String ownerId, UUID voucherId, Integer points, String idempotencyKey);

    /**
     * Ambil semua mutasi (ledger entries) bagi akaun tertentu.
     *
     * @param accountId ID akaun
     * @return senarai mutasi akaun
     */
    java.util.List<com.alfagift.finledger.domain.entities.LedgerEntry> getAccountMutations(UUID accountId);
}