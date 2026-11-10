package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.PaymentProof;
import com.alfagift.finledger.dto.PaymentProofDownload;
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
                List.of("image/png", "image/jpeg", "image/webp", "application/pdf"), 300);
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
}
