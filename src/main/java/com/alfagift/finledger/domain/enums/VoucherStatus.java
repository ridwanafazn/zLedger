package com.alfagift.finledger.domain.enums;

/**
 * Status siklus hidup voucher.
 */
public enum VoucherStatus {
    /** Baru dibuat, belum bisa diredeem. */
    DRAFT,
    /** Aktif dan dapat diredeem selama periode berlaku. */
    ACTIVE,
    /** Dinonaktifkan manual oleh admin. */
    INACTIVE,
    /** Melewati {@code endsAt} atau kuota habis. */
    EXPIRED
}
