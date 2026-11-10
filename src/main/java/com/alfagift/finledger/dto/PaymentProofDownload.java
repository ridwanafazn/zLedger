package com.alfagift.finledger.dto;

import java.io.InputStream;

/**
 * Hasil unduhan bukti bayar untuk distream langsung ke klien.
 *
 * @param content     stream isi file (pemanggil wajib menutup)
 * @param filename    nama file asli
 * @param contentType MIME type
 * @param sizeBytes   ukuran byte
 */
public record PaymentProofDownload(InputStream content,
                                   String filename,
                                   String contentType,
                                   long sizeBytes) {
}
