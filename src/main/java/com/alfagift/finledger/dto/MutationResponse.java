package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.EntryType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Satu baris mutasi akun untuk kebutuhan tampilan riwayat di frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MutationResponse {

    private UUID id;
    private UUID transactionId;
    private UUID accountId;
    private EntryType entryType;
    private BigDecimal amount;
    private String description;
    private LocalDateTime createdAt;
}
