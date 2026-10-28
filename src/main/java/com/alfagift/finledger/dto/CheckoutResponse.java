package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response payload untuk endpoint pembayaran multi-tender.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutResponse {

    private UUID transactionId;
    private String ownerId;
    private String merchantId;
    private Currency currency;
    private BigDecimal totalAmount;
    private BigDecimal balanceRemaining;
    private LocalDateTime processedAt;
    private String status;
}