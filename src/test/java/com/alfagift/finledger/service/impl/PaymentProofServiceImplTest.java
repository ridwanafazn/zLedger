package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.PaymentProof;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.PaymentProofStatus;
import com.alfagift.finledger.domain.enums.UploadMethod;
import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofPresignRequest;
import com.alfagift.finledger.dto.PaymentProofPresignResponse;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.infrastructure.storage.LocalFileSystemBlobStorage;
import com.alfagift.finledger.infrastructure.storage.StorageProperties;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.PaymentProofRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link PaymentProofServiceImpl} dengan storage lokal sungguhan
 * di direktori sementara.
 */
@ExtendWith(MockitoExtension.class)
class PaymentProofServiceImplTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String UPLOADER = "cust-proof-1";

    /** PNG 1x1 piksel (signature valid). */
    private static final byte[] PNG_BYTES = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    @Mock
    private PaymentProofRepository paymentProofRepository;
    @Mock
    private JournalTransactionRepository journalTransactionRepository;
    @Mock
    private FinLedgerMetrics metrics;

    private LocalFileSystemBlobStorage blobStorage;
    private PaymentProofServiceImpl service;

    @BeforeEach
    void setUp() {
        blobStorage = new LocalFileSystemBlobStorage(tempDir);
        StorageProperties properties = new StorageProperties(
                "local", "finledger-proofs", "us-east-1", "", "", "", true,
                tempDir.toString(), 1024,
                List.of("image/png", "image/jpeg", "image/webp", "application/pdf"), 300,
                50_000_000L, 365, "0 0 3 * * *");
        service = new PaymentProofServiceImpl(paymentProofRepository, journalTransactionRepository,
                blobStorage, properties, metrics);
    }

    private MockMultipartFile png(String filename) {
        return new MockMultipartFile("file", filename, "image/png", PNG_BYTES);
    }

    private PaymentProof storedProof() {
        return PaymentProof.builder()
                .id(UUID.randomUUID())
                .transactionId(TRANSACTION_ID)
                .uploaderId(UPLOADER)
                .objectKey("payment-proofs/2026/11/existing.png")
                .originalFilename("bukti.png")
                .contentType("image/png")
                .sizeBytes(PNG_BYTES.length)
                .checksumSha256("abc")
                .status(PaymentProofStatus.UPLOADED)
                .uploadMethod(UploadMethod.MULTIPART)
                .build();
    }

    @Nested
    @DisplayName("upload")
    class Upload {

        @Test
        @DisplayName("Menyimpan blob dan metadata beserta checksum SHA-256")
        void shouldStoreBlobAndMetadata() throws IOException {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);
            when(paymentProofRepository.existsByTransactionIdAndChecksumSha256(eq(TRANSACTION_ID), anyString()))
                    .thenReturn(false);
            when(paymentProofRepository.save(any(PaymentProof.class))).thenAnswer(invocation -> {
                PaymentProof proof = invocation.getArgument(0);
                proof.setId(UUID.randomUUID());
                return proof;
            });

            PaymentProofResponse response = service.upload(TRANSACTION_ID, png("bukti transfer.png"), UPLOADER);

            assertThat(response.getContentType()).isEqualTo("image/png");
            assertThat(response.getSizeBytes()).isEqualTo(PNG_BYTES.length);
            assertThat(response.getChecksumSha256()).hasSize(64);
            assertThat(response.getOriginalFilename()).isEqualTo("bukti_transfer.png");
            assertThat(response.getContentUrl()).contains(response.getId().toString());
            assertThat(response.getPresignedUrl()).isNull();

            ArgumentCaptor<PaymentProof> captor = ArgumentCaptor.forClass(PaymentProof.class);
            verify(paymentProofRepository).save(captor.capture());
            String objectKey = captor.getValue().getObjectKey();
            assertThat(objectKey).startsWith("payment-proofs/").endsWith(".png");
            assertThat(Files.exists(tempDir.resolve(objectKey))).isTrue();
        }

        @Test
        @DisplayName("Menolak tipe file di luar whitelist")
        void shouldRejectDisallowedContentType() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);

            MockMultipartFile exe = new MockMultipartFile("file", "evil.exe",
                    "application/x-msdownload", PNG_BYTES);

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, exe, UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tidak diizinkan");
            verify(paymentProofRepository, never()).save(any());
        }

        @Test
        @DisplayName("Menolak file yang signature-nya tidak cocok dengan Content-Type")
        void shouldRejectSpoofedContentType() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);

            MockMultipartFile spoofed = new MockMultipartFile("file", "fake.png", "image/png",
                    "bukan-gambar".getBytes());

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, spoofed, UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signature tidak cocok");
            verify(paymentProofRepository, never()).save(any());
        }

        @Test
        @DisplayName("Menolak file melebihi batas ukuran")
        void shouldRejectOversizedFile() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);

            byte[] big = new byte[2048];
            System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);
            MockMultipartFile oversized = new MockMultipartFile("file", "big.png", "image/png", big);

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, oversized, UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("melebihi batas");
        }

        @Test
        @DisplayName("Menolak duplikat file untuk transaksi yang sama")
        void shouldRejectDuplicateChecksum() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);
            when(paymentProofRepository.existsByTransactionIdAndChecksumSha256(eq(TRANSACTION_ID), anyString()))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, png("sama.png"), UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sudah pernah diunggah");
        }

        @Test
        @DisplayName("Transaksi tidak ada harus 404")
        void shouldRejectUnknownTransaction() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, png("a.png"), UPLOADER))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        @DisplayName("File kosong harus ditolak")
        void shouldRejectEmptyFile() {
            MockMultipartFile empty = new MockMultipartFile("file", "kosong.png", "image/png", new byte[0]);

            assertThatThrownBy(() -> service.upload(TRANSACTION_ID, empty, UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tidak boleh kosong");
        }
    }

    @Nested
    @DisplayName("akses berkas")
    class Access {

        @Test
        @DisplayName("Non-admin hanya melihat bukti miliknya")
        void shouldFilterByOwnerForNonAdmin() {
            PaymentProof mine = storedProof();
            PaymentProof other = storedProof();
            other.setUploaderId("orang-lain");
            when(paymentProofRepository.findByTransactionIdOrderByCreatedAtAsc(TRANSACTION_ID))
                    .thenReturn(List.of(mine, other));

            List<PaymentProofResponse> result = service.findByTransaction(TRANSACTION_ID, UPLOADER, false);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUploaderId()).isEqualTo(UPLOADER);
        }

        @Test
        @DisplayName("Admin melihat semua bukti pada transaksi")
        void shouldReturnAllForAdmin() {
            PaymentProof mine = storedProof();
            PaymentProof other = storedProof();
            other.setUploaderId("orang-lain");
            when(paymentProofRepository.findByTransactionIdOrderByCreatedAtAsc(TRANSACTION_ID))
                    .thenReturn(List.of(mine, other));

            assertThat(service.findByTransaction(TRANSACTION_ID, "admin", true)).hasSize(2);
        }

        @Test
        @DisplayName("Mengunduh bukti milik sendiri mengembalikan isi file")
        void shouldDownloadOwnProof() throws IOException {
            String key = "payment-proofs/2026/11/own.png";
            blobStorage.put(key, new java.io.ByteArrayInputStream(PNG_BYTES), PNG_BYTES.length, "image/png");

            PaymentProof proof = storedProof();
            proof.setObjectKey(key);
            when(paymentProofRepository.findById(proof.getId())).thenReturn(Optional.of(proof));

            PaymentProofDownload download = service.download(proof.getId(), UPLOADER, false);

            assertThat(download.content().readAllBytes()).isEqualTo(PNG_BYTES);
            assertThat(download.contentType()).isEqualTo("image/png");
            assertThat(download.sizeBytes()).isEqualTo(PNG_BYTES.length);
            download.content().close();
        }

        @Test
        @DisplayName("Mengunduh bukti milik user lain harus 403")
        void shouldForbidForeignDownload() {
            PaymentProof proof = storedProof();
            when(paymentProofRepository.findById(proof.getId())).thenReturn(Optional.of(proof));

            assertThatThrownBy(() -> service.download(proof.getId(), "penyusup", false))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("Menghapus bukti menghapus metadata dan blob")
        void shouldDeleteMetadataAndBlob() {
            String key = "payment-proofs/2026/11/hapus.png";
            blobStorage.put(key, new java.io.ByteArrayInputStream(PNG_BYTES), PNG_BYTES.length, "image/png");

            PaymentProof proof = storedProof();
            proof.setObjectKey(key);
            when(paymentProofRepository.findById(proof.getId())).thenReturn(Optional.of(proof));

            service.delete(proof.getId());

            verify(paymentProofRepository).delete(proof);
            assertThat(blobStorage.exists(key)).isFalse();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Presigned upload & retensi                                         */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("presign & complete")
    class Presigned {

        private FakeS3BlobStorage fakeS3;
        private PaymentProofServiceImpl s3Service;

        @BeforeEach
        void setUpFakeS3() {
            fakeS3 = new FakeS3BlobStorage();
            StorageProperties properties = new StorageProperties(
                    "s3", "finledger-proofs", "us-east-1", "http://localhost:9091", "foo", "bar", true,
                    tempDir.toString(), 1024,
                    List.of("image/png", "image/jpeg", "image/webp", "application/pdf"), 300,
                    50_000_000L, 365, "0 0 3 * * *");
            s3Service = new PaymentProofServiceImpl(paymentProofRepository, journalTransactionRepository,
                    fakeS3, properties, metrics);
        }

        private PaymentProofPresignRequest presignRequest(String checksum) {
            return PaymentProofPresignRequest.builder()
                    .transactionId(TRANSACTION_ID)
                    .filename("bukti besar.png")
                    .contentType("image/png")
                    .sizeBytes((long) PNG_BYTES.length)
                    .checksumSha256(checksum)
                    .build();
        }

        @Test
        @DisplayName("Storage lokal tidak mendukung presigned upload -> 409")
        void shouldRejectPresignOnLocalStorage() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> service.presign(presignRequest(null), UPLOADER))
                    .isInstanceOf(com.alfagift.finledger.infrastructure.ResourceConflictException.class)
                    .hasMessageContaining("tidak mendukung presigned upload");
        }

        @Test
        @DisplayName("Presign membuat metadata PENDING + URL PUT bertanda tangan")
        void shouldCreatePendingProofWithPresignedUrl() {
            when(journalTransactionRepository.existsById(TRANSACTION_ID)).thenReturn(true);
            when(paymentProofRepository.save(any(PaymentProof.class))).thenAnswer(invocation -> {
                PaymentProof proof = invocation.getArgument(0);
                proof.setId(UUID.randomUUID());
                return proof;
            });

            PaymentProofPresignResponse response = s3Service.presign(presignRequest(null), UPLOADER);

            assertThat(response.getUploadUrl()).startsWith("http://fake-put/");
            assertThat(response.getMethod()).isEqualTo("PUT");
            assertThat(response.getHeaders()).containsEntry("Content-Type", "image/png");
            assertThat(response.getCompleteUrl()).contains("/complete");
            assertThat(response.getExpiresAt()).isAfter(java.time.LocalDateTime.now());

            ArgumentCaptor<PaymentProof> captor = ArgumentCaptor.forClass(PaymentProof.class);
            verify(paymentProofRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(PaymentProofStatus.PENDING);
            assertThat(captor.getValue().getUploadMethod()).isEqualTo(UploadMethod.PRESIGNED);
            assertThat(captor.getValue().getChecksumSha256()).isNull();
        }

        @Test
        @DisplayName("Presign menolak ukuran di atas batas presigned")
        void shouldRejectOversizedPresign() {
            PaymentProofPresignRequest request = presignRequest(null);
            request.setSizeBytes(60_000_000L);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s3Service.presign(request, UPLOADER))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("melebihi batas presigned");
        }

        @Test
        @DisplayName("Complete memverifikasi objek lalu menandai UPLOADED")
        void shouldCompletePresignedUpload() {
            PaymentProof pending = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/11/pending.png")
                    .originalFilename("bukti.png")
                    .contentType("image/png")
                    .sizeBytes(PNG_BYTES.length)
                    .status(PaymentProofStatus.PENDING)
                    .uploadMethod(UploadMethod.PRESIGNED)
                    .expiresAt(java.time.LocalDateTime.now().plusMinutes(5))
                    .build();

            fakeS3.objects.put(pending.getObjectKey(), PNG_BYTES);
            fakeS3.types.put(pending.getObjectKey(), "image/png");

            when(paymentProofRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
            when(paymentProofRepository.existsByTransactionIdAndChecksumSha256(eq(TRANSACTION_ID), anyString()))
                    .thenReturn(false);
            when(paymentProofRepository.save(any(PaymentProof.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            PaymentProofResponse response = s3Service.complete(pending.getId(), UPLOADER, false);

            assertThat(response.getStatus()).isEqualTo(PaymentProofStatus.UPLOADED);
            assertThat(response.getChecksumSha256()).hasSize(64);
            assertThat(response.getChecksumSha256()).isEqualTo(sha256Hex(PNG_BYTES));
            assertThat(response.getContentUrl()).contains("/content");
            assertThat(response.getPresignedUrl()).isNotNull();
        }

        @Test
        @DisplayName("Complete menolak objek yang belum diunggah")
        void shouldRejectCompleteWithoutObject() {
            PaymentProof pending = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/11/missing.png")
                    .originalFilename("bukti.png")
                    .contentType("image/png")
                    .sizeBytes(PNG_BYTES.length)
                    .status(PaymentProofStatus.PENDING)
                    .uploadMethod(UploadMethod.PRESIGNED)
                    .expiresAt(java.time.LocalDateTime.now().plusMinutes(5))
                    .build();

            when(paymentProofRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> s3Service.complete(pending.getId(), UPLOADER, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("belum diunggah");
        }

        @Test
        @DisplayName("Complete menolak ukuran objek yang tidak sesuai")
        void shouldRejectSizeMismatch() {
            PaymentProof pending = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/11/short.png")
                    .originalFilename("bukti.png")
                    .contentType("image/png")
                    .sizeBytes(PNG_BYTES.length + 100)
                    .status(PaymentProofStatus.PENDING)
                    .uploadMethod(UploadMethod.PRESIGNED)
                    .expiresAt(java.time.LocalDateTime.now().plusMinutes(5))
                    .build();

            fakeS3.objects.put(pending.getObjectKey(), PNG_BYTES);
            fakeS3.types.put(pending.getObjectKey(), "image/png");
            when(paymentProofRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> s3Service.complete(pending.getId(), UPLOADER, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Ukuran objek tidak sesuai");
        }

        @Test
        @DisplayName("Complete menolak presigned yang sudah kedaluwarsa")
        void shouldRejectExpiredPresign() {
            PaymentProof pending = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/11/expired.png")
                    .originalFilename("bukti.png")
                    .contentType("image/png")
                    .sizeBytes(PNG_BYTES.length)
                    .status(PaymentProofStatus.PENDING)
                    .uploadMethod(UploadMethod.PRESIGNED)
                    .expiresAt(java.time.LocalDateTime.now().minusMinutes(1))
                    .build();

            when(paymentProofRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> s3Service.complete(pending.getId(), UPLOADER, false))
                    .isInstanceOf(com.alfagift.finledger.infrastructure.ResourceConflictException.class)
                    .hasMessageContaining("kedaluwarsa");
        }
    }

    @Nested
    @DisplayName("retensi")
    class Retention {

        @Test
        @DisplayName("Menghapus bukti UPLOADED yang tua dan PENDING yang kedaluwarsa")
        void shouldPurgeExpiredAndStale() {
            PaymentProof old = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/01/old.png")
                    .originalFilename("old.png")
                    .contentType("image/png")
                    .sizeBytes(PNG_BYTES.length)
                    .checksumSha256("aaa")
                    .status(PaymentProofStatus.UPLOADED)
                    .uploadMethod(UploadMethod.MULTIPART)
                    .build();
            PaymentProof stale = PaymentProof.builder()
                    .id(UUID.randomUUID())
                    .transactionId(TRANSACTION_ID)
                    .uploaderId(UPLOADER)
                    .objectKey("payment-proofs/2026/11/stale.png")
                    .originalFilename("stale.png")
                    .contentType("image/png")
                    .sizeBytes(10)
                    .status(PaymentProofStatus.PENDING)
                    .uploadMethod(UploadMethod.PRESIGNED)
                    .expiresAt(java.time.LocalDateTime.now().minusHours(1))
                    .build();

            blobStorage.put(old.getObjectKey(), new java.io.ByteArrayInputStream(PNG_BYTES),
                    PNG_BYTES.length, "image/png");
            blobStorage.put(stale.getObjectKey(), new java.io.ByteArrayInputStream(new byte[10]), 10, "image/png");

            when(paymentProofRepository.findByStatusAndCreatedAtBefore(eq(PaymentProofStatus.UPLOADED), any()))
                    .thenReturn(List.of(old));
            when(paymentProofRepository.findByStatusAndExpiresAtBefore(eq(PaymentProofStatus.PENDING), any()))
                    .thenReturn(List.of(stale));

            int purged = service.purgeExpired(365);

            assertThat(purged).isEqualTo(2);
            verify(paymentProofRepository).delete(old);
            verify(paymentProofRepository).delete(stale);
            assertThat(blobStorage.exists(old.getObjectKey())).isFalse();
            assertThat(blobStorage.exists(stale.getObjectKey())).isFalse();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Fake S3 storage                                                    */
    /* ------------------------------------------------------------------ */

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Storage palsu yang meniru perilaku S3 untuk presigned upload & HEAD.
     */
    private static final class FakeS3BlobStorage implements com.alfagift.finledger.infrastructure.storage.BlobStorage {

        private final java.util.Map<String, byte[]> objects = new java.util.HashMap<>();
        private final java.util.Map<String, String> types = new java.util.HashMap<>();

        @Override
        public String type() {
            return "s3";
        }

        @Override
        public void put(String key, java.io.InputStream content, long size, String contentType) {
            try {
                objects.put(key, content.readAllBytes());
                types.put(key, contentType);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public java.io.InputStream get(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new com.alfagift.finledger.infrastructure.storage.BlobNotFoundException(key);
            }
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
            types.remove(key);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public java.util.Optional<String> presignedDownloadUrl(String key, java.time.Duration ttl) {
            return java.util.Optional.of("http://fake-get/" + key);
        }

        @Override
        public java.util.Optional<com.alfagift.finledger.infrastructure.storage.PresignedUpload>
        presignedUploadUrl(String key, String contentType, long sizeBytes, String checksumSha256Base64,
                           java.time.Duration ttl) {
            java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("Content-Type", contentType);
            headers.put("Content-Length", Long.toString(sizeBytes));
            if (checksumSha256Base64 != null) {
                headers.put("x-amz-checksum-sha256", checksumSha256Base64);
            }
            return java.util.Optional.of(
                    new com.alfagift.finledger.infrastructure.storage.PresignedUpload(
                            "http://fake-put/" + key, headers));
        }

        @Override
        public java.util.Optional<com.alfagift.finledger.infrastructure.storage.BlobStat> stat(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                return java.util.Optional.empty();
            }
            String base64 = java.util.Base64.getEncoder().encodeToString(
                    java.util.HexFormat.of().parseHex(sha256Hex(bytes)));
            return java.util.Optional.of(new com.alfagift.finledger.infrastructure.storage.BlobStat(
                    bytes.length, types.get(key), base64));
        }
    }
}
