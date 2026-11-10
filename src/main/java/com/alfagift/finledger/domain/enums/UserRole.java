package com.alfagift.finledger.domain.enums;

/**
 * Role RBAC yang dikenal sistem.
 * <p>
 * Disimpan sebagai string di tabel {@code app_user_roles} agar mudah dibaca
 * saat audit; pemetaan ke Spring Security memakai prefiks {@code ROLE_}.
 */
public enum UserRole {
    /** Akses penuh: kelola akun, voucher, reversal, dan user. */
    ADMIN,
    /** Pemilik akun merchant. */
    MERCHANT,
    /** Pengguna akhir. */
    CUSTOMER
}
