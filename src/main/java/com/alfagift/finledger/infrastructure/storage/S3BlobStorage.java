package com.alfagift.finledger.infrastructure.storage;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.core.exception.SdkException;
import org.springframework.http.HttpHeaders;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Penyimpanan blob pada layanan S3-compatible (MinIO lokal, AWS S3, GCS interop).
 * <p>
 * Bucket dibuat otomatis bila belum ada (memudahkan dev), dan kegagalan
 * infrastruktur dilempar sebagai {@link BlobStorageException} agar dipetakan ke 503.
 */
@Slf4j
public class S3BlobStorage implements BlobStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3BlobStorage(S3Client client, S3Presigner presigner, String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        ensureBucket();
    }

    @Override
    public String type() {
        return "s3";
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType) {
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .contentLength(size)
                            .build(),
                    RequestBody.fromInputStream(content, size));
        } catch (SdkException ex) {
            throw new BlobStorageException("Gagal menyimpan blob " + key + " ke S3", ex);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return stream;
        } catch (NoSuchKeyException ex) {
            throw new BlobNotFoundException(key);
        } catch (SdkException ex) {
            if (ex instanceof S3Exception s3 && s3.statusCode() == 404) {
                throw new BlobNotFoundException(key);
            }
            throw new BlobStorageException("Gagal membaca blob " + key + " dari S3", ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException ex) {
            throw new BlobStorageException("Gagal menghapus blob " + key + " dari S3", ex);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (SdkException ex) {
            if (ex instanceof S3Exception s3 && s3.statusCode() == 404) {
                return false;
            }
            throw new BlobStorageException("Gagal memeriksa blob " + key + " di S3", ex);
        }
    }

    @Override
    public Optional<String> presignedDownloadUrl(String key, Duration ttl) {
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                        .build());
        return Optional.of(presigned.url().toString());
    }

    @Override
    public Optional<PresignedUpload> presignedUploadUrl(String key, String contentType, long sizeBytes,
                                                        String checksumSha256Base64, Duration ttl) {
        PutObjectRequest.Builder putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(sizeBytes);
        if (checksumSha256Base64 != null && !checksumSha256Base64.isBlank()) {
            putRequest.checksumSHA256(checksumSha256Base64);
        }

        PresignedPutObjectRequest presigned = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .putObjectRequest(putRequest.build())
                        .build());

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.CONTENT_TYPE, contentType);
        headers.put(HttpHeaders.CONTENT_LENGTH, Long.toString(sizeBytes));
        if (checksumSha256Base64 != null && !checksumSha256Base64.isBlank()) {
            headers.put("x-amz-checksum-sha256", checksumSha256Base64);
        }
        return Optional.of(new PresignedUpload(presigned.url().toString(), headers));
    }

    @Override
    public Optional<BlobStat> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new BlobStat(head.contentLength(), head.contentType(), head.checksumSHA256()));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (SdkException ex) {
            if (ex instanceof S3Exception s3 && s3.statusCode() == 404) {
                return Optional.empty();
            }
            throw new BlobStorageException("Gagal membaca metadata blob " + key + " dari S3", ex);
        }
    }

    private void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (SdkException ex) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("Bucket S3 '{}' dibuat otomatis", bucket);
            } catch (SdkException createEx) {
                throw new BlobStorageException("Bucket S3 '" + bucket + "' tidak tersedia", createEx);
            }
        }
    }
}
