package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.VoucherStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request perubahan status voucher (ADMIN).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherStatusRequest {

    @NotNull(message = "Status wajib diisi")
    private VoucherStatus status;
}
