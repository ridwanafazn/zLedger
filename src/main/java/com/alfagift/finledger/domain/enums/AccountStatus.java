package com.alfagift.finledger.domain.enums;

/**
 * Status akun dalam sistem.
 * <p>
 * Transisi legal:
 * <ul>
 *   <li>ACTIVE <-> SUSPENDED</li>
 *   <li>ACTIVE/SUSPENDED -> CLOSED</li>
 *   <li>CLOSED -> ACTIVE (reopen, admin only)</li>
 * </ul>
 */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED
}