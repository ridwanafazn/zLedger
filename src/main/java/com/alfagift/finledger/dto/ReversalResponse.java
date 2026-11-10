package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.ReferenceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Respons reversal: transaksi kompensasi yang dibuat untuk membalik transaksi asal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversalResponse {

    private UUID originalTransactionId;
    private UUID reversalTransactionId;
    private ReferenceType referenceType;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private String reason;
    private LocalDateTime processedAt;
    private String status;
}
