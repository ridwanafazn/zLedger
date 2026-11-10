package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.MutationResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.ReversalResponse;
import com.alfagift.finledger.dto.TransactionDetailResponse;
import com.alfagift.finledger.dto.TransactionSummaryResponse;
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
     * Ambil mutasi (ledger entries) bagi akaun tertentu dengan pagination.
     *
     * @param accountId ID akaun
     * @param page      halaman (0-based)
     * @param size      ukuran halaman (maks 100)
     * @return halaman mutasi akun
     */
    PageResponse<MutationResponse> getAccountMutations(UUID accountId, int page, int size);

    /**
     * Daftar transaksi jurnal (paginated), terbaru lebih dulu.
     *
     * @param page          nomor halaman (0-based)
     * @param size          ukuran halaman (maks 100)
     * @param referenceType filter opsional (PAYMENT/REDEMPTION/REFUND/REWARD)
     * @param accountId     filter opsional: transaksi yang menyentuh akun ini
     * @param from          filter opsional tanggal awal (inklusif)
     * @param to            filter opsional tanggal akhir (inklusif)
     * @return halaman transaksi
     */
    PageResponse<TransactionSummaryResponse> listTransactions(
            int page, int size, String referenceType, UUID accountId,
            java.time.LocalDate from, java.time.LocalDate to);

    /**
     * Detail satu transaksi beserta seluruh entri debit/credit.
     */
    TransactionDetailResponse getTransaction(UUID transactionId);

    /**
     * Membalik transaksi dengan entri kompensasi (bukan UPDATE/DELETE).
     * <p>
     * Aturan immutability: transaksi asal tetap utuh; reversal adalah jurnal baru
     * bertipe {@code REFUND} yang menunjuk ke transaksi asal.
     *
     * @param transactionId ID transaksi asal
     * @param reason        alasan reversal
     * @return detail jurnal kompensasi
     */
    ReversalResponse reverseTransaction(UUID transactionId, String reason);
}