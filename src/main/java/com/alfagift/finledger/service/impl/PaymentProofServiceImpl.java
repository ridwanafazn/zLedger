package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.PaymentProof;
import com.alfagift.finledger.domain.enums.PaymentProofStatus;
import com.alfagift.finledger.domain.enums.UploadMethod;
import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofPresignRequest;
import com.alfagift.finledger.dto.PaymentProofPresignResponse;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.infrastructure.ResourceConflictException;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.infrastructure.storage.BlobStat;
import com.alfagift.finledger.infrastructure.storage.BlobStorage;
import com.alfagift.finledger.infrastructure.storage.BlobStorageException;
import com.alfagift.finledger.infrastructure.storage.PresignedUpload;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Implementasi {@link PaymentProofService}.
 * <p>
 * Dua jalur unggah:
 * <ol>
 *   <li><b>multipart</b> â€” validasi transaksi â†’ validasi tipe & signature â†’
 *       SHA-256 â†’ simpan blob â†’ simpan metadata. Bila metadata gagal disimpan
 *       (mis. duplikat checksum), blob dihapus kembali agar tidak ada objek yatim.</li>
 *   <li><b>presigned</b> â€” metadata PENDING + URL PUT bertanda tangan. Setelah klien
 *       mengunggah, {@code complete()} memverifikasi ukuran/tiped/checksum objek
 *       sebelum menandainya UPLOADED.</li>
 * </ol>
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

    /* ------------------------------------------------------------------ */
    /* Multipart upload                                                   */
    /* ------------------------------------------------------------------ */

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
        requireAllowedContentType(contentType);

        byte[] bytes = readBounded(file);
        if (!FileSignatureValidator.matches(contentType, bytes)) {
            metrics.proofRejected("signature");
            throw new IllegalArgumentException(
                    "Isi file tidak sesuai dengan tipe " + contentType + " (signature tidak cocok)");
        }

        String checksum = sha256Hex(bytes);
        if (paymentProofRepository.existsByTransactionIdAndChecksumSha256(transactionId, checksum)) {
            metrics.proofRejected("duplicate");
            throw new IllegalArgumentException("File yang sama sudah pernah diunggah untuk transaksi ini");
        }

        String filename = sanitizeFilename(file.getOriginalFilename(), contentType);
        String objectKey = newObjectKey(contentType);

        blobStorage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, contentType);

        PaymentProof proof = PaymentProof.builder()
                .transactionId(transactionId)
                .uploaderId(uploaderId)
                .objectKey(objectKey)
                .originalFilename(filename)
                .contentType(contentType)
                .sizeBytes(bytes.length)
                .checksumSha256(checksum)
                .status(PaymentProofStatus.UPLOADED)
                .uploadMethod(UploadMethod.MULTIPART)
                .build();

        try {
            PaymentProof saved = paymentProofRepository.save(proof);
            metrics.proofUploaded(contentType, bytes.length);
            log.info("Bukti bayar {} diunggah (multipart) untuk transaksi {} oleh {} ({} byte, storage={})",
                    saved.getId(), transactionId, uploaderId, bytes.length, blobStorage.type());
            return toResponse(saved);
        } catch (DataIntegrityViolationException ex) {
            blobStorage.delete(objectKey);
            metrics.proofRejected("duplicate");
            throw ex;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Presigned upload (file besar)                                      */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional
    public PaymentProofPresignResponse presign(PaymentProofPresignRequest request, String uploaderId) {
        String contentType = normalizeContentType(request.getContentType());
        requireAllowedContentType(contentType);

        if (request.getSizeBytes() == null || request.getSizeBytes() <= 0) {
            throw new IllegalArgumentException("sizeBytes wajib lebih besar dari 0");
        }
        if (request.getSizeBytes() > storageProperties.presignMaxBytes()) {
            metrics.proofRejected("too_large");
            throw new IllegalArgumentException(String.format(
                    "Ukuran file %d byte melebihi batas presigned upload %d byte",
                    request.getSizeBytes(), storageProperties.presignMaxBytes()));
        }
        if (!journalTransactionRepository.existsById(request.getTransactionId())) {
            throw new EntityNotFoundException("Transaksi tidak ditemukan: " + request.getTransactionId());
        }

        String checksumHex = request.getChecksumSha256() == null
                ? null : request.getChecksumSha256().toLowerCase(Locale.ROOT);
        if (checksumHex != null
                && paymentProofRepository.existsByTransactionIdAndChecksumSha256(
                        request.getTransactionId(), checksumHex)) {
            metrics.proofRejected("duplicate");
            throw new IllegalArgumentException("File yang sama sudah pernah diunggah untuk transaksi ini");
        }

        String filename = sanitizeFilename(request.getFilename(), contentType);
        String objectKey = newObjectKey(contentType);
        Duration ttl = Duration.ofSeconds(storageProperties.presignedTtlSeconds());
        LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);

        PresignedUpload presigned = blobStorage
                .presignedUploadUrl(objectKey, contentType, request.getSizeBytes(),
                        checksumHex == null ? null : hexToBase64(checksumHex), ttl)
                .orElseThrow(() -> new ResourceConflictException(
                        "Storage '" + blobStorage.type() + "' tidak mendukung presigned upload; "
                                + "gunakan multipart atau set app.storage.type=s3"));

        PaymentProof proof = paymentProofRepository.save(PaymentProof.builder()
                .transactionId(request.getTransactionId())
                .uploaderId(uploaderId)
                .objectKey(objectKey)
                .originalFilename(filename)
                .contentType(contentType)
                .sizeBytes(request.getSizeBytes())
                .status(PaymentProofStatus.PENDING)
                .uploadMethod(UploadMethod.PRESIGNED)
                .expiresAt(expiresAt)
                .build());

        metrics.proofPresigned(contentType);
        log.info("Presigned upload dibuat: proof={}, transaksi={}, {} byte, kedaluwarsa {}",
                proof.getId(), proof.getTransactionId(), request.getSizeBytes(), expiresAt);

        return PaymentProofPresignResponse.builder()
                .proofId(proof.getId())
                .transactionId(proof.getTransactionId())
                .objectKey(objectKey)
                .uploadUrl(presigned.url())
                .method("PUT")
                .headers(presigned.requiredHeaders())
                .sizeBytes(proof.getSizeBytes())
                .expiresAt(expiresAt)
                .completeUrl("/api/v1/payment-proofs/" + proof.getId() + "/complete")
                .build();
    }

    @Override
    @Transactional
    public PaymentProofResponse complete(UUID proofId, String requesterId, boolean admin) {
        PaymentProof proof = findAuthorized(proofId, requesterId, admin);

        if (proof.getStatus() == PaymentProofStatus.UPLOADED) {
            throw new ResourceConflictException("Bukti bayar " + proofId + " sudah berstatus UPLOADED");
        }
        if (proof.isExpired(LocalDateTime.now())) {
            metrics.proofRejected("presign_expired");
            throw new ResourceConflictException(
                    "Presigned upload untuk bukti bayar " + proofId + " sudah kedaluwarsa");
        }

        BlobStat stat = blobStorage.stat(proof.getObjectKey())
                .orElseThrow(() -> {
                    metrics.proofRejected("object_missing");
                    return new IllegalArgumentException(
                            "Objek belum diunggah ke storage untuk bukti bayar " + proofId);
                });

        if (stat.sizeBytes() != proof.getSizeBytes()) {
            metrics.proofRejected("size_mismatch");
            throw new IllegalArgumentException(String.format(
                    "Ukuran objek tidak sesuai: diharapkan %d byte, ditemukan %d byte",
                    proof.getSizeBytes(), stat.sizeBytes()));
        }
        if (stat.contentType() != null
                && !normalizeContentType(stat.contentType()).equals(proof.getContentType())) {
            metrics.proofRejected("content_type_mismatch");
            throw new IllegalArgumentException(String.format(
                    "Tipe objek tidak sesuai: diharapkan %s, ditemukan %s",
                    proof.getContentType(), stat.contentType()));
        }

        String checksumHex = stat.checksumSha256Base64() != null
                ? base64ToHex(stat.checksumSha256Base64())
                : sha256Hex(readAll(proof.getObjectKey()));

        if (paymentProofRepository.existsByTransactionIdAndChecksumSha256(
                proof.getTransactionId(), checksumHex)) {
            // Objek duplikat: hapus agar tidak menumpuk di storage.
            blobStorage.delete(proof.getObjectKey());
            metrics.proofRejected("duplicate");
            throw new IllegalArgumentException("File yang sama sudah pernah diunggah untuk transaksi ini");
        }

        proof.setChecksumSha256(checksumHex);
        proof.setStatus(PaymentProofStatus.UPLOADED);
        proof.setExpiresAt(null);
        PaymentProof saved = paymentProofRepository.save(proof);

        metrics.proofUploaded(saved.getContentType(), saved.getSizeBytes());
        log.info("Presigned upload diverifikasi: proof={}, transaksi={}, {} byte, checksum={}",
                saved.getId(), saved.getTransactionId(), saved.getSizeBytes(),
                checksumHex.substring(0, 16) + "...");

        return toResponse(saved);
    }

    /* ------------------------------------------------------------------ */
    /* Read                                                               */
    /* ------------------------------------------------------------------ */

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
        if (proof.getStatus() != PaymentProofStatus.UPLOADED) {
            throw new ResourceConflictException(
                    "Bukti bayar " + proofId + " belum selesai diunggah (status " + proof.getStatus() + ")");
        }
        InputStream content = blobStorage.get(proof.getObjectKey());
        return new PaymentProofDownload(content, proof.getOriginalFilename(),
                proof.getContentType(), proof.getSizeBytes());
    }

    /* ------------------------------------------------------------------ */
    /* Delete & retention                                                 */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional
    public void delete(UUID proofId) {
        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new EntityNotFoundException("Bukti bayar tidak ditemukan: " + proofId));

        paymentProofRepository.delete(proof);
        deleteBlobQuietly(proof);
        log.info("Bukti bayar {} dihapus (blob {})", proofId, proof.getObjectKey());
    }

    @Override
    @Transactional
    public int purgeExpired(int olderThanDays) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusDays(Math.max(olderThanDays, 0));

        List<PaymentProof> expired = new java.util.ArrayList<>(
                paymentProofRepository.findByStatusAndCreatedAtBefore(PaymentProofStatus.UPLOADED, cutoff));
        expired.addAll(paymentProofRepository.findByStatusAndExpiresAtBefore(PaymentProofStatus.PENDING, now));

        int purged = 0;
        for (PaymentProof proof : expired) {
            paymentProofRepository.delete(proof);
            deleteBlobQuietly(proof);
            purged++;
        }
        if (purged > 0) {
            metrics.proofPurged(purged);
            log.info("Retensi bukti bayar: {} objek dihapus (cutoff upload={}, batas simpan={} hari)",
                    purged, cutoff, olderThanDays);
        }
        return purged;
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private void deleteBlobQuietly(PaymentProof proof) {
        try {
            blobStorage.delete(proof.getObjectKey());
        } catch (BlobStorageException ex) {
            // Metadata sudah terhapus; kegagalan storage dicatat untuk pembersihan manual.
            log.error("Metadata bukti {} terhapus tetapi blob {} gagal dihapus: {}",
                    proof.getId(), proof.getObjectKey(), ex.getMessage());
        }
    }

    private PaymentProof findAuthorized(UUID proofId, String requesterId, boolean admin) {
        PaymentProof proof = paymentProofRepository.findById(proofId)
                .orElseThrow(() -> new EntityNotFoundException("Bukti bayar tidak ditemukan: " + proofId));

        if (!admin && !proof.getUploaderId().equals(requesterId)) {
            throw new AccessDeniedException("Bukti bayar ini bukan milik Anda");
        }
        return proof;
    }

    private PaymentProofResponse toResponse(PaymentProof proof) {
        boolean uploaded = proof.getStatus() == PaymentProofStatus.UPLOADED;
        String presigned = uploaded
                ? blobStorage.presignedDownloadUrl(proof.getObjectKey(),
                        Duration.ofSeconds(storageProperties.presignedTtlSeconds())).orElse(null)
                : null;

        return PaymentProofResponse.builder()
                .id(proof.getId())
                .transactionId(proof.getTransactionId())
                .uploaderId(proof.getUploaderId())
                .originalFilename(proof.getOriginalFilename())
                .contentType(proof.getContentType())
                .sizeBytes(proof.getSizeBytes())
                .checksumSha256(proof.getChecksumSha256())
                .status(proof.getStatus())
                .uploadMethod(proof.getUploadMethod())
                .createdAt(proof.getCreatedAt())
                .contentUrl(uploaded ? "/api/v1/payment-proofs/" + proof.getId() + "/content" : null)
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

    private void requireAllowedContentType(String contentType) {
        if (!storageProperties.allowedContentTypes().contains(contentType)) {
            metrics.proofRejected("content_type");
            throw new IllegalArgumentException(String.format(
                    "Tipe file '%s' tidak diizinkan. Pilihan: %s",
                    contentType, storageProperties.allowedContentTypes()));
        }
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

    private byte[] readAll(String objectKey) {
        try (InputStream input = blobStorage.get(objectKey)) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new BlobStorageException("Gagal membaca objek " + objectKey, ex);
        }
    }

    private String newObjectKey(String contentType) {
        return String.format("payment-proofs/%s/%s.%s",
                LocalDate.now().format(DATE_PATH), UUID.randomUUID(), EXTENSION_BY_TYPE.get(contentType));
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

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Algoritma SHA-256 tidak tersedia", ex);
        }
    }

    private String hexToBase64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }

    private String base64ToHex(String base64) {
        return HexFormat.of().formatHex(Base64.getDecoder().decode(base64));
    }
}
