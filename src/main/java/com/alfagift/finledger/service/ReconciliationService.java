package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.ReconciliationRunResponse;

import java.util.UUID;

/**
 * Rekonsiliasi saldo akun terhadap ledger (Fase I).
 * <p>
 * Rekonsiliasi membandingkan cached {@code balance} setiap akun dengan saldo
 * ekspektasi {@code opening_balance + CREDIT - DEBIT}, serta memeriksa bahwa
 * setiap jurnal seimbang dan memiliki minimal dua entri.
 */
public interface ReconciliationService {

    /**
     * Menjalankan rekonsiliasi penuh dan menyimpan hasilnya sebagai audit trail.
     *
     * @param triggeredBy username admin yang memicu (boleh null)
     * @param notes       catatan opsional
     */
    ReconciliationRunResponse run(String triggeredBy, String notes);

    PageResponse<ReconciliationRunResponse> list(int page, int size);

    ReconciliationRunResponse get(UUID runId);
}
