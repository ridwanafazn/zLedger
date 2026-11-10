package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request untuk mengubah status akun.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountStatusRequest {

    @NotNull(message = "Status wajib diisi")
    private AccountStatus status;

    @Size(max = 255, message = "Alasan maksimal 255 karakter")
    private String reason;
}