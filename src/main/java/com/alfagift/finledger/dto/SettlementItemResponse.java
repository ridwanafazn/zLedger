package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Satu jurnal yang membentuk batch settlement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementItemResponse {

    private UUID id;
    private UUID transactionId;
    private ReferenceType referenceType;
    private EntryType entryType;
    private BigDecimal amount;
    /** Positif untuk PAYMENT, negatif untuk REFUND. */
    private BigDecimal signedAmount;
    private LocalDateTime occurredAt;
}
