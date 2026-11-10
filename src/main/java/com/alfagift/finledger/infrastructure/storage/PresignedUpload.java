package com.alfagift.finledger.infrastructure.storage;

import java.util.Map;

/**
 * Hasil pembuatan URL unggah sementara (presigned PUT).
 *
 * @param url             URL yang harus dipakai klien untuk PUT
 * @param requiredHeaders header yang WAJIB dikirim klien; ikut ditandatangani,
 *                        sehingga menghilangkannya membuat signature tidak valid
 */
public record PresignedUpload(String url, Map<String, String> requiredHeaders) {
}
