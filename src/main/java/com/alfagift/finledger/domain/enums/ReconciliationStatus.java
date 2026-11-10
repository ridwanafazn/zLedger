package com.alfagift.finledger.domain.enums;

/**
 * Hasil satu eksekusi rekonsiliasi.
 * <ul>
 *   <li>BALANCED — semua saldo akun cocok dengan ledger dan semua jurnal seimbang</li>
 *   <li>DISCREPANCY — ditemukan minimal satu selisih saldo atau jurnal tidak seimbang</li>
 * </ul>
 */
public enum ReconciliationStatus {
    BALANCED,
    DISCREPANCY
}
