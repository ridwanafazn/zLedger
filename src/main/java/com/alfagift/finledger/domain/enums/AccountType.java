package com.alfagift.finledger.domain.enums;

/**
 * Jenis akaun dalam sistem ledger double-entry.
 * <p>
 * Mensokong model perakaunan classic:
 * <ul>
 *   <li>ASSET — Aset (contoh: dompet pelanggan, dompet merchant)</li>
 *   <li>LIABILITY — Kewajiban (contoh: kredit yang harus dibayar)</li>
 *   <li>REVENUE — Pendapatan (contoh: pool revenue merchant)</li>
 *   <li>EXPENSE — Belanja (contoh: pool promocode sistem)</li>
 * </ul>
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    REVENUE,
    EXPENSE
}