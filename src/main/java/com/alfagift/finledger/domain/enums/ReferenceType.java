package com.alfagift.finledger.domain.enums;

/**
 * Jenis rujukan peristiwa yang memicu pergerakan ledger.
 */
public enum ReferenceType {
    PAYMENT,     // Pembayaran multi-tender
    REDEMPTION,  // Tebasan flash sale / voucher
    REFUND,      // Pengembalian wang / poin
    REWARD       // Hadiah poin loyalty (asinkron via Kafka)
}