package com.alfagift.finledger.infrastructure;

/**
 * Pelanggaran aturan state settlement (periode bertumpuk, payout ganda, dsb.).
 * <p>
 * Dipetakan ke HTTP 409 Conflict: permintaan valid secara sintaks, tetapi
 * bertentangan dengan state bisnis saat ini.
 */
public class SettlementConflictException extends RuntimeException {

    public SettlementConflictException(String message) {
        super(message);
    }
}
