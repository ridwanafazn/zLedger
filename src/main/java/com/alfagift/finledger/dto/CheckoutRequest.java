package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.Currency;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request payload untuk endpoint pembayaran multi-tender {@code POST /api/v1/ledger/checkout}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {

    @NotNull(message = "ID pelanggan wajib diisi")
    private String ownerId;

    @NotNull(message = "ID merchant wajib diisi")
    private String merchantId;

    @NotNull(message = "Total jumlah wajib diisi")
    @DecimalMin(value = "0.0", inclusive = false, message = "Total harus lebih besar dari nol")
    private BigDecimal totalAmount;

    @NotEmpty(message = "Minimal satu tender wajib disediakan")
    private List<TenderDetail> tenders;

    @NotBlank(message = "X-Idempotency-Key wajib diisi melalui header")
    private String idempotencyKey;

    @NotNull(message = "Mata wang utama wajib diisi")
    private Currency currency;

    /**
     * Rincian satu tender (contoh: IDR 50.000, A_POIN 10.000).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TenderDetail {

        @NotNull(message = "Mata wang tender wajib diisi")
        private Currency currency;

        @NotNull(message = "Jumlah tender wajib diisi")
        @DecimalMin(value = "0.0", inclusive = false, message = "Jumlah tender harus lebih besar dari nol")
        private BigDecimal amount;

        @NotBlank(message = "Deskripsi tender wajib diisi")
        private String description;
    }
}