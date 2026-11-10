package com.alfagift.finledger.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hasil redeem voucher.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherRedemptionResponse {

    private UUID voucherId;
    private String voucherCode;
    private String ownerId;
    private UUID transactionId;
    private BigDecimal pointsSpent;
    private int remainingQuota;
    private LocalDateTime redeemedAt;
    private String status;
}
