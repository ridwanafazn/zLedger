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
 * Ringkasan satu transaksi jurnal untuk endpoint daftar transaksi.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionSummaryResponse {

    private UUID id;
    private String idempotencyKey;
    private ReferenceType referenceType;
    private String description;
    private LocalDateTime createdAt;
    private int entryCount;
    private BigDecimal totalAmount;
}
