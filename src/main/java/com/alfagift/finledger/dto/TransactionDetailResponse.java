package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Detail transaksi jurnal beserta seluruh entri debit/credit (audit trail).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDetailResponse {

    private UUID id;
    private String idempotencyKey;
    private ReferenceType referenceType;
    private String description;
    private LocalDateTime createdAt;
    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private boolean balanced;
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private UUID id;
        private UUID accountId;
        private String ownerId;
        private AccountType accountType;
        private Currency currency;
        private EntryType entryType;
        private BigDecimal amount;
        private LocalDateTime createdAt;
    }
}
