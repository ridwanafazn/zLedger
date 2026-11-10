package com.alfagift.finledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request pembuatan presigned upload untuk bukti bayar berukuran besar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProofPresignRequest {

    @NotNull(message = "transactionId wajib diisi")
    private UUID transactionId;

    @NotBlank(message = "filename wajib diisi")
    @Size(max = 255, message = "filename maksimal 255 karakter")
    private String filename;

    @NotBlank(message = "contentType wajib diisi")
    @Size(max = 100, message = "contentType maksimal 100 karakter")
    private String contentType;

    @NotNull(message = "sizeBytes wajib diisi")
    @Positive(message = "sizeBytes harus lebih besar dari 0")
    private Long sizeBytes;

    /**
     * SHA-256 dalam bentuk hex (opsional, 64 karakter). Bila dikirim, nilainya
     * ikut ditandatangani pada presigned URL sehingga storage menolak file yang
     * checksum-nya berbeda.
     */
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "checksumSha256 harus 64 karakter hex")
    private String checksumSha256;
}
