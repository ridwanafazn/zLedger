package com.alfagift.finledger.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Konfigurasi penyimpanan blob.
 *
 * @param type              {@code local} atau {@code s3}
 * @param bucket            nama bucket (S3/MinIO)
 * @param region            region S3 (MinIO mengabaikan nilai ini)
 * @param endpoint          endpoint S3-compatible, mis. {@code http://localhost:9000}; kosong = AWS
 * @param accessKey         access key (MinIO/AWS)
 * @param secretKey         secret key
 * @param pathStyleAccess   wajib {@code true} untuk MinIO
 * @param localBasePath     direktori dasar storage lokal
 * @param maxFileSizeBytes  batas ukuran file yang diterima
 * @param allowedContentTypes MIME type yang diperbolehkan
 * @param presignedTtlSeconds masa berlaku URL presigned
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("local") String type,
        @DefaultValue("finledger-proofs") String bucket,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("") String endpoint,
        @DefaultValue("") String accessKey,
        @DefaultValue("") String secretKey,
        @DefaultValue("true") boolean pathStyleAccess,
        @DefaultValue("./target/blob-store") String localBasePath,
        @DefaultValue("5242880") long maxFileSizeBytes,
        @DefaultValue({"image/png", "image/jpeg", "image/webp", "application/pdf"})
        List<String> allowedContentTypes,
        @DefaultValue("300") long presignedTtlSeconds,
        @DefaultValue("52428800") long presignMaxBytes,
        @DefaultValue("365") int retentionDays,
        @DefaultValue("0 0 3 * * *") String retentionCron) {

    public StorageProperties {
        if (type == null || type.isBlank()) {
            type = "local";
        }
        type = type.trim().toLowerCase();
        if (!type.equals("local") && !type.equals("s3")) {
            throw new IllegalArgumentException("app.storage.type harus 'local' atau 's3', diterima: " + type);
        }
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("app.storage.max-file-size-bytes harus > 0");
        }
        if (presignMaxBytes <= 0) {
            throw new IllegalArgumentException("app.storage.presign-max-bytes harus > 0");
        }
        if (retentionDays < 0) {
            throw new IllegalArgumentException("app.storage.retention-days tidak boleh negatif");
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of("image/png", "image/jpeg", "image/webp", "application/pdf");
        }
        if (type.equals("s3") && (accessKey == null || accessKey.isBlank())) {
            throw new IllegalArgumentException("app.storage.access-key wajib diisi saat type=s3");
        }
    }
}
