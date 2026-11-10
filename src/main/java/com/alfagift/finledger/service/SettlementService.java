package com.alfagift.finledger.service;

import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.SettlementCreateRequest;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Settlement pendapatan merchant (Fase I).
 * <p>
 * Alur: hitung (preview) → buat batch PENDING → bayar (payout jurnal
 * {@code SETTLEMENT}) atau batalkan. Batch bersifat snapshot dan immutable
 * setelah dibuat; payout tidak pernah mengubah nilai batch.
 */
public interface SettlementService {

    /**
     * Simulasi perhitungan settlement tanpa menyimpan data.
     *
     * @param feePercent null berarti memakai nilai konfigurasi default
     */
    SettlementPreviewResponse preview(String merchantId, Currency currency,
                                      LocalDateTime periodStart, LocalDateTime periodEnd,
                                      BigDecimal feePercent);

    /**
     * Membuat batch settlement PENDING.
     * <p>
     * Menolak periode yang bertumpuk dengan batch lain (selain CANCELLED) dan
     * menolak net negatif. Idempotent terhadap header {@code X-Idempotency-Key}.
     */
    SettlementResponse create(SettlementCreateRequest request, String idempotencyKey);

    SettlementResponse get(UUID settlementId);

    PageResponse<SettlementResponse> search(String merchantId, String status, int page, int size);

    /**
     * Membayar batch: memposting jurnal {@code SETTLEMENT} (DEBIT akun merchant,
     * CREDIT akun clearing sistem) dan menandai batch PAID.
     */
    SettlementResponse pay(UUID settlementId, String idempotencyKey);

    /**
     * Membatalkan batch yang belum dibayar sehingga periodenya bisa dipakai ulang.
     */
    SettlementResponse cancel(UUID settlementId, String reason);
}
