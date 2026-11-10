package com.alfagift.finledger.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pembatalan batch settlement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementCancelRequest {

    @Size(max = 500, message = "reason maksimal 500 karakter")
    private String reason;
}
