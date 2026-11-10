package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.enums.ReferenceType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Jurnal yang tidak seimbang: selisih {@code totalDebit - totalCredit}.
 */
public record JournalBalanceCheck(UUID id, ReferenceType referenceType, String description, BigDecimal imbalance) {
}
