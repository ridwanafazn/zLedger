package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Hasil simulasi settlement tanpa menyimpan apa pun.
 * <p>
 * Dipakai admin untuk memverifikasi angka sebelum membuat batch permanen.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementPreviewResponse {

    private String merchantId;
    private Currency currency;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private BigDecimal grossAmount;
    private BigDecimal refundAmount;
    private BigDecimal feePercent;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private int paymentCount;
    private int refundCount;
    /** False bila netAmount negatif (refund melebihi penjualan) sehingga batch tidak boleh dibuat. */
    private boolean payable;
    private String message;
}
