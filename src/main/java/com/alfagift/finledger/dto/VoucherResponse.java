package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.VoucherStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representasi voucher untuk katalog frontend.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private BigDecimal pointsCost;
    private int totalQuota;
    private int remainingQuota;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private VoucherStatus status;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
