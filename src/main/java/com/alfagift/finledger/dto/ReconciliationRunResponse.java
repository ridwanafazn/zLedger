package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.ReconciliationStatus;
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
 * Hasil/riwayat satu run rekonsiliasi.
 * <p>
 * {@code discrepancies} hanya diisi pada endpoint detail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconciliationRunResponse {

    private UUID id;
    private ReconciliationStatus status;
    private int accountsChecked;
    private int discrepancyCount;
    private int unbalancedJournalCount;
    private BigDecimal totalAbsVariance;
    private String triggeredBy;
    private String notes;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    private List<ReconciliationDiscrepancyResponse> discrepancies;
}
