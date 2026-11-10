package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.SettlementStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Representasi batch settlement.
 * <p>
 * Dipakai untuk list (tanpa {@code items}) dan detail (dengan {@code items}).
 * Field {@code items} hanya muncul pada endpoint detail agar respons list tetap ringan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementResponse {

    private UUID id;
    private String merchantId;
    private com.alfagift.finledger.domain.enums.Currency currency;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private BigDecimal grossAmount;
    private BigDecimal refundAmount;
    private BigDecimal feePercent;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private SettlementStatus status;
    private UUID payoutTransactionId;
    private String idempotencyKey;
    private String notes;
    private long itemCount;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime paidAt;
    private LocalDateTime cancelledAt;

    /** Diisi hanya pada endpoint detail. */
    private List<SettlementItemResponse> items;
}
