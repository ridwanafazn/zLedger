package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.ReconciliationRunRequest;
import com.alfagift.finledger.dto.ReconciliationRunResponse;
import com.alfagift.finledger.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Rekonsiliasi saldo akun terhadap ledger (ADMIN).
 */
@RestController
@RequestMapping("/api/v1/reconciliation")
@RequiredArgsConstructor
@Tag(name = "Reconciliation", description = "Rekonsiliasi saldo akun vs ledger dan audit temuan")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    @PostMapping("/runs")
    @Operation(summary = "Jalankan rekonsiliasi",
            description = "Membandingkan saldo setiap akun dengan ledger, memeriksa keseimbangan jurnal, "
                    + "lalu menyimpan hasilnya sebagai audit trail.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Rekonsiliasi selesai (BALANCED atau DISCREPANCY)")
    })
    public ResponseEntity<ReconciliationRunResponse> run(
            @Valid @RequestBody(required = false) ReconciliationRunRequest request,
            Authentication authentication) {

        String triggeredBy = authentication == null ? null : authentication.getName();
        String notes = request == null ? null : request.getNotes();

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reconciliationService.run(triggeredBy, notes));
    }

    @GetMapping("/runs")
    @Operation(summary = "Riwayat rekonsiliasi", description = "Daftar run rekonsiliasi terbaru dengan pagination.")
    public ResponseEntity<PageResponse<ReconciliationRunResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(reconciliationService.list(page, size));
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Detail run rekonsiliasi", description = "Termasuk seluruh temuan (selisih saldo / jurnal).")
    public ResponseEntity<ReconciliationRunResponse> get(@PathVariable UUID runId) {
        return ResponseEntity.ok(reconciliationService.get(runId));
    }
}
