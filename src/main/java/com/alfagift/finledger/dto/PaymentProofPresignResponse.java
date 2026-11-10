package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Instruksi unggah langsung ke object storage.
 * <p>
 * Klien wajib mengirim persis {@code method} + {@code headers} + isi file ke
 * {@code uploadUrl}, lalu memanggil endpoint {@code complete}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentProofPresignResponse {

    private UUID proofId;
    private UUID transactionId;
    private String objectKey;
    private String uploadUrl;
    private String method;
    private Map<String, String> headers;
    private long sizeBytes;
    private LocalDateTime expiresAt;
    private String completeUrl;
}
