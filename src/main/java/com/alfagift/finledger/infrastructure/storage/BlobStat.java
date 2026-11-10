package com.alfagift.finledger.infrastructure.storage;

/**
 * Metadata objek yang dibaca langsung dari storage (HEAD), dipakai untuk
 * memverifikasi unggahan presigned sebelum metadata ditandai UPLOADED.
 *
 * @param sizeBytes           ukuran objek sebenarnya
 * @param contentType         MIME type yang tersimpan (bisa null pada storage lokal)
 * @param checksumSha256Base64 checksum SHA-256 base64 dari storage (bisa null)
 */
public record BlobStat(long sizeBytes, String contentType, String checksumSha256Base64) {
}
