package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Satu temuan rekonsiliasi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationDiscrepancyResponse {

    private UUID id;
    private UUID accountId;
    /** {@code BALANCE_MISMATCH} atau {@code UNBALANCED_JOURNAL}. */
    private String discrepancyType;
    private BigDecimal expectedBalance;
    private BigDecimal actualBalance;
    private BigDecimal variance;
    private String detail;
    private LocalDateTime createdAt;
}
