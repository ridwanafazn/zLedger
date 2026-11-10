package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.Currency;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response untuk inquiry saldo (lightweight).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {

    private UUID accountId;
    private String ownerId;
    private Currency currency;
    private BigDecimal balance;
    private AccountStatus status;
    private LocalDateTime asOf;
}