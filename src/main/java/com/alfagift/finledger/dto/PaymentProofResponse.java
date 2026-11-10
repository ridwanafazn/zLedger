package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata bukti bayar yang dikembalikan ke klien.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProofResponse {

    private UUID id;
    private UUID transactionId;
    private String uploaderId;
    private String originalFilename;
    private String contentType;
    private long sizeBytes;
    private String checksumSha256;
    private LocalDateTime createdAt;

    /** Path API untuk mengunduh lewat aplikasi (selalu tersedia). */
    private String contentUrl;

    /**
     * URL presigned langsung ke object storage; hanya tersedia saat
     * {@code app.storage.type=s3} (menghemat bandwidth aplikasi).
     */
    private String presignedUrl;
}
