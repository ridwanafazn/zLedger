package com.alfagift.finledger.domain.enums;

/**
 * Status batch settlement merchant.
 * <ul>
 *   <li>PENDING — batch dihitung dan menunggu payout</li>
 *   <li>PAID — payout sudah diposting sebagai jurnal {@code SETTLEMENT}</li>
 *   <li>CANCELLED — batch dibatalkan sebelum payout; periode boleh dipakai ulang</li>
 * </ul>
 */
public enum SettlementStatus {
    PENDING,
    PAID,
    CANCELLED
}
