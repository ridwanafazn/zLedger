package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Kontribusi satu jurnal terhadap batch settlement merchant.
 *
 * @param transactionId  id jurnal
 * @param referenceType  jenis jurnal (PAYMENT atau REFUND)
 * @param entryType      sisi entri pada akun merchant
 * @param amount         nilai absolut entri
 * @param occurredAt     waktu jurnal dibuat
 */
public record SettlementContribution(UUID transactionId,
                                     ReferenceType referenceType,
                                     EntryType entryType,
                                     BigDecimal amount,
                                     LocalDateTime occurredAt) {
}
