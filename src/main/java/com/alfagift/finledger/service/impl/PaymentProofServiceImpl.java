package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.PaymentProof;
import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.infrastructure.storage.BlobStorage;
import com.alfagift.finledger.infrastructure.storage.BlobStorageException;
import com.alfagift.finledger.infrastructure.storage.StorageProperties;
import com.alfagift.finledger.infrastructure.validation.FileSignatureValidator;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.PaymentProofRepository;
import com.alfagift.finledger.service.PaymentProofService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Implementasi {@link PaymentProofService}.
 * <p>
 * Alur unggah: validasi transaksi → validasi tipe & signature file → hitung
 * SHA-256 → simpan blob → simpan metadata. Bila metadata gagal disimpan
 * (mis. duplikat checksum), blob dihapus kembali agar tidak ada objek yatim.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentProofServiceImpl implements PaymentProofService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final Map<String, String> EXTENSION_BY_TYPE = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "application/pdf", "pdf");

    private final PaymentProofRepository paymentProofRepository;
    private final JournalTransactionRepository journalTransactionRepository;
    private final BlobStorage blobStorage;
    private final StorageProperties storageProperties;
    private final FinLedgerMetrics metrics;

    @Override
    @Transactional
    public PaymentProofResponse upload(UUID transactionId, MultipartFile file, String uploaderId) {
        if (file == null || file.isEmpty()) {
            metrics.proofRejected("empty_file");
            throw new IllegalArgumentException("File bukti bayar wajib diisi dan tidak boleh kosong");
        }
        if (!journalTransactionRepository.existsById(transactionId)) {
            throw new EntityNotFoundException("Transaksi tidak ditemukan: " + transactionId);
        }

        String contentType = normalizeContentType(file.getContentType());
        if (!storageProperties.allowedContentTypes().contains(contentType)) {
            metrics.proofRejected("content_type");
            throw new IllegalArgumentException(String.format(
                    "Tipe file '%s' tidak diizinkan. Pilihan: %s",
                    contentType, storageProperties.allowedContentTypes()));
        }

        byte[] bytes = readBounded(file);
        if (!FileSignatureValidator.matches(contentType, bytes)) {
            metrics.proofRejected("signature");
            throw new IllegalArgumentException(
                    "Isi file tidak sesuai dengan tipe " + contentType + " (signature tidak cocok)");
        }

        String checksum = sha256(bytes);
        if (paymentProofRepository.existsByTransactionIdAndChecksumSha256(transactionId, checksum)) {
            metrics.proofRejected("duplicate");
            throw new IllegalArgumentException("File yang sama sudah pernah diunggah untuk transaksi ini");
        }

        String filename = sanitizeFilename(file.getOriginalFilename(), contentType);
        String objectKey = String.format("payment-proofs/%s/%s.%s",
                LocalDate.now().format(DATE_PATH), UUID.randomUUID(), EXTENSION_BY_TYPE.get(contentType));

        blobStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);

        PaymentProof proof = PaymentProof.builder()
                .transactionId(transactionId)
                .uploaderId(uploaderId)
                .objectKey(objectKey)
                .originalFilename(filename)
                .contentType(contentType)
                .sizeBytes(bytes.length)
                .checksumSha256(checksum)
                .build();

        try {
            PaymentProof saved = paymentProofRepository.save(proof);
            metrics.proofUploaded(contentType, bytes.length);
            log.info("Bukti bayar {} diunggah untuk transaksi {} oleh {} ({} byte, storage={})",
                    saved.getId(), transactionId, uploaderId, bytes.length, blobStorage.type());
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            blobStorage.delete(objectKey);
            metrics.proofRejected("duplicate");
            throw ex;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentProofResponse> findByTransaction(UUID transactionId, String requesterId, boolean admin) {
        List<PaymentProof> proofs = paymentProofRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId);
        return proofs.stream()
                .filter(proof -> admin || proof.getUploaderId().equals(requesterId))
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentProofResponse get(UUID proofId, String requesterId, boolean admin) {
        return toResponse(findAuthorized(proofId, requesterId, admin));
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentProofDownload download(UUID proofId, String requesterId, boolean admin) {
        PaymentProof proof = findAuthorized(proofId, requesterId, admin);
        InputStream content = blobStorage.get(proof.getObjectKey());
        return new PaymentProofDownload(content, proof.getOriginalFilename(),
                proof.getContentType(), proof.getSizeBytes());
    }

    @Override
    @Transactional
    public void delete(UUID proofId) {
        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new EntityNotFoundException("Bukti bayar tidak ditemukan: " + proofId));

        paymentProofRepository.delete(proof);
        try {
            blobStorage.delete(proof.getObjectKey());
        } catch (BlobStorageException ex) {
            // Metadata sudah terhapus; kegagalan storage dicatat untuk pembersihan manual.
            log.error("Metadata bukti {} terhapus tetapi blob {} gagal dihapus: {}",
                    proofId, proof.getObjectKey(), ex.getMessage());
        }
        log.info("Bukti bayar {} dihapus (blob {})", proofId, proof.getObjectKey());
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private PaymentProof findAuthorized(UUID proofId, String requesterId, boolean admin) {
        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new EntityNotFoundException("Bukti bayar tidak ditemukan: " + proofId));

        if (!admin && !proof.getUploaderId().equals(requesterId)) {
            throw new AccessDeniedException("Bukti bayar ini bukan milik Anda");
        }
        return proof;
    }

    private PaymentProofResponse toResponse(PaymentProof proof) {
        String presigned = blobStorage
                .presignedDownloadUrl(proof.getObjectKey(), Duration.ofSeconds(storageProperties.presignedTtlSeconds()))
                .orElse(null);

        return PaymentProofResponse.builder()
                .id(proof.getId())
                .transactionId(proof.getTransactionId())
                .uploaderId(proof.getUploaderId())
                .originalFilename(proof.getOriginalFilename())
                .contentType(proof.getContentType())
                .sizeBytes(proof.getSizeBytes())
                .checksumSha256(proof.getChecksumSha256())
                .createdAt(proof.getCreatedAt())
                .contentUrl("/api/v1/payment-proofs/" + proof.getId() + "/content")
                .presignedUrl(presigned)
                .build();
    }

    private String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Content-Type file wajib diisi");
        }
        int semicolon = raw.indexOf(';');
        return (semicolon >= 0 ? raw.substring(0, semicolon) : raw).trim().toLowerCase(Locale.ROOT);
    }

    private byte[] readBounded(MultipartFile file) {
        long max = storageProperties.maxFileSizeBytes();
        if (file.getSize() > max) {
            metrics.proofRejected("too_large");
            throw new IllegalArgumentException("Ukuran file melebihi batas " + max + " byte");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) Math.min(Integer.MAX_VALUE, max + 1));
            if (bytes.length > max) {
                metrics.proofRejected("too_large");
                throw new IllegalArgumentException("Ukuran file melebihi batas " + max + " byte");
            }
            if (bytes.length == 0) {
                metrics.proofRejected("empty_file");
                throw new IllegalArgumentException("File bukti bayar kosong");
            }
            return bytes;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Gagal membaca file yang diunggah: " + ex.getMessage());
        }
    }

    private String sanitizeFilename(String original, String contentType) {
        String fallback = "proof." + EXTENSION_BY_TYPE.get(contentType);
        if (original == null || original.isBlank()) {
            return fallback;
        }
        String candidate = original.replace("\\", "/");
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (candidate.isBlank() || candidate.startsWith(".")) {
            return fallback;
        }
        return candidate.length() > 255 ? candidate.substring(0, 255) : candidate;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritma SHA-256 tidak tersedia", ex);
        }
    }
}
