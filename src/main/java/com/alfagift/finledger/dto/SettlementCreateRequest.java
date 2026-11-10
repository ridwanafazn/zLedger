package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.Currency;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request pembuatan batch settlement merchant.
 * <p>
 * Periode bersifat {@code [periodStart, periodEnd)} — batas akhir eksklusif
 * agar batch berurutan tidak menghitung transaksi yang sama dua kali.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementCreateRequest {

    @NotNull(message = "merchantId wajib diisi")
    @Size(max = 64, message = "merchantId maksimal 64 karakter")
    private String merchantId;

    @NotNull(message = "currency wajib diisi")
    private Currency currency;

    @NotNull(message = "periodStart wajib diisi")
    private LocalDateTime periodStart;

    @NotNull(message = "periodEnd wajib diisi")
    private LocalDateTime periodEnd;

    /**
     * Persentase fee merchant. Bila kosong, dipakai nilai konfigurasi
     * {@code app.settlement.fee-percent}.
     */
    @DecimalMin(value = "0.0", message = "feePercent tidak boleh negatif")
    @DecimalMax(value = "100.0", message = "feePercent maksimal 100")
    private BigDecimal feePercent;

    @Size(max = 500, message = "notes maksimal 500 karakter")
    private String notes;
}
