package com.alfagift.finledger.domain.entities;

import com.alfagift.finledger.domain.enums.PaymentProofStatus;
import com.alfagift.finledger.domain.enums.UploadMethod;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Metadata bukti bayar (transfer, resi, atau capture).
 * <p>
 * Byte file berada di object storage (MinIO/S3/local) dan hanya dirujuk lewat
 * {@code objectKey}. Integritas file diverifikasi lewat checksum SHA-256 yang
 * unik per transaksi, sehingga upload file yang sama dua kali ditolak.
 * <p>
 * Untuk file besar, metadata dibuat lebih dulu dengan status {@code PENDING}
 * beserta presigned URL; setelah klien mengunggah langsung ke storage, endpoint
 * complete memverifikasi objek dan mengubah status menjadi {@code UPLOADED}.
 */
@Entity
@Table(name = "payment_proofs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_payment_proofs_object_key", columnNames = "object_key"),
                @UniqueConstraint(name = "uk_payment_proofs_checksum",
                        columnNames = {"transaction_id", "checksum_sha256"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PaymentProof {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "transaction_id", nullable = false, columnDefinition = "UUID")
    private UUID transactionId;

    @Column(name = "uploader_id", nullable = false, length = 64)
    private String uploaderId;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Null selama status PENDING (checksum belum bisa dihitung). */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private PaymentProofStatus status = PaymentProofStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_method", nullable = false, length = 16)
    @Builder.Default
    private UploadMethod uploadMethod = UploadMethod.MULTIPART;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public boolean isPending() {
        return status == PaymentProofStatus.PENDING;
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }
}
