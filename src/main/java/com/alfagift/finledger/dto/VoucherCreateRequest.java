package com.alfagift.finledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request pembuatan voucher (ADMIN).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoucherCreateRequest {

    @NotBlank(message = "Kode voucher wajib diisi")
    @Size(max = 64, message = "Kode maksimal 64 karakter")
    @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Kode hanya boleh huruf besar, angka, '-' dan '_'")
    private String code;

    @NotBlank(message = "Nama voucher wajib diisi")
    @Size(max = 128, message = "Nama maksimal 128 karakter")
    private String name;

    @Size(max = 1000, message = "Deskripsi maksimal 1000 karakter")
    private String description;

    @NotNull(message = "Harga poin wajib diisi")
    @DecimalMin(value = "0.0001", message = "Harga poin harus lebih dari nol")
    private BigDecimal pointsCost;

    @Min(value = 1, message = "Kuota minimal 1")
    private int totalQuota;

    @NotNull(message = "Waktu mulai wajib diisi")
    private LocalDateTime startsAt;

    @NotNull(message = "Waktu berakhir wajib diisi")
    private LocalDateTime endsAt;
}
