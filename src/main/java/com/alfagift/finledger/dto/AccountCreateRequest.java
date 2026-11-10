package com.alfagift.finledger.dto;

import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request untuk membuat akun baru.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountCreateRequest {

    @NotBlank(message = "Owner ID wajib diisi")
    @Size(max = 64, message = "Owner ID maksimal 64 karakter")
    private String ownerId;

    @NotBlank(message = "Nama akun wajib diisi")
    @Size(max = 128, message = "Nama akun maksimal 128 karakter")
    private String accountName;

    @NotNull(message = "Tipe akun wajib diisi")
    private AccountType accountType;

    @NotNull(message = "Mata uang wajib diisi")
    private Currency currency;

    @DecimalMin(value = "0.0", inclusive = true, message = "Saldo awal harus >= 0")
    @Builder.Default
    private BigDecimal initialBalance = BigDecimal.ZERO;
}