package com.alfagift.finledger.domain.enums;

/**
 * Status event pada tabel outbox.
 */
public enum OutboxStatus {
    /** Menunggu dipublikasikan ke Kafka. */
    PENDING,
    /** Sudah berhasil dipublikasikan. */
    PUBLISHED,
    /** Gagal setelah batas percobaan tercapai (butuh intervensi manual). */
    FAILED
}
