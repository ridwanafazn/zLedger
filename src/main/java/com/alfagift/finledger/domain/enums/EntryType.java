package com.alfagift.finledger.domain.enums;

/**
 * Jenis entri ledger dalam double-entry bookkeeping.
 * <ul>
 *   <li>DEBIT — Menambah aset / mengurangi kewajiban dan ekuiti</li>
 *   <li>CREDIT — Mengurangi aset / menambah kewajiban dan ekuiti</li>
 * </ul>
 * <p>
 * Dalam konteks FinLedger:
 * <ul>
 *   <li>DEBIT pada akaun pelanggan (LIABILITY) = menarik / mendeduct poin atau balans</li>
 *   <li>CREDIT pada akaun merchant (REVENUE) = menambah pendapatan merchant</li>
 * </ul>
 */
public enum EntryType {
    DEBIT,
    CREDIT
}