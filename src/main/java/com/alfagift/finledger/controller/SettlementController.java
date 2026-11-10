package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.SettlementCancelRequest;
import com.alfagift.finledger.dto.SettlementCreateRequest;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;
import com.alfagift.finledger.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Batch settlement merchant: preview, pembuatan, payout, dan pembatalan (ADMIN).
 */
@RestController
@RequestMapping("/api/v1/settlements")
@RequiredArgsConstructor
@Tag(name = "Settlement", description = "Batch settlement merchant, fee, dan payout")
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping("/preview")
    @Operation(summary = "Preview settlement",
            description = "Menghitung gross, refund, fee, dan net untuk periode tertentu tanpa menyimpan data (ADMIN).")
    public ResponseEntity<SettlementPreviewResponse> preview(
            @Parameter(description = "ID merchant (owner akun ledger)") @RequestParam String merchantId,
            @Parameter(description = "Mata uang settlement") @RequestParam(defaultValue = "IDR") Currency currency,
            @Parameter(description = "Awal periode (ISO-8601, inklusif)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Akhir periode (ISO-8601, eksklusif)")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @Parameter(description = "Fee dalam persen; kosong = nilai konfigurasi")
            @RequestParam(required = false) BigDecimal feePercent) {

        return ResponseEntity.ok(settlementService.preview(merchantId, currency, from, to, feePercent));
    }

    @PostMapping
    @Operation(summary = "Buat batch settlement",
            description = "Membuat batch PENDING dari jurnal PAYMENT/REFUND pada periode [start, end). "
                    + "Menolak periode yang bertumpuk dengan batch lain. Idempotency wajib via header.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Batch dibuat"),
            @ApiResponse(responseCode = "400", description = "Periode tidak valid / net tidak positif"),
            @ApiResponse(responseCode = "409", description = "Periode bertumpuk / duplicate idempotency key")
    })
    public ResponseEntity<SettlementResponse> create(
            @Valid @RequestBody SettlementCreateRequest request,
            @Parameter(hidden = true) @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(settlementService.create(request, idempotencyKey));
    }

    @GetMapping
    @Operation(summary = "Daftar batch settlement", description = "Filter merchant/status dengan pagination.")
    public ResponseEntity<PageResponse<SettlementResponse>> search(
            @RequestParam(required = false) String merchantId,
            @Parameter(description = "PENDING / PAID / CANCELLED") @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(settlementService.search(merchantId, status, page, size));
    }

    @GetMapping("/{settlementId}")
    @Operation(summary = "Detail batch settlement", description = "Termasuk rincian jurnal yang membentuk batch.")
    public ResponseEntity<SettlementResponse> get(@PathVariable UUID settlementId) {
        return ResponseEntity.ok(settlementService.get(settlementId));
    }

    @PostMapping("/{settlementId}/pay")
    @Operation(summary = "Bayar batch settlement",
            description = "Memposting jurnal SETTLEMENT (DEBIT akun merchant, CREDIT akun clearing) dan "
                    + "menandai batch PAID. Satu batch hanya bisa dibayar sekali.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payout berhasil"),
            @ApiResponse(responseCode = "400", description = "Saldo merchant tidak cukup / akun merchant tidak ada"),
            @ApiResponse(responseCode = "409", description = "Batch sudah dibayar atau dibatalkan")
    })
    public ResponseEntity<SettlementResponse> pay(
            @PathVariable UUID settlementId,
            @Parameter(hidden = true) @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        return ResponseEntity.ok(settlementService.pay(settlementId, idempotencyKey));
    }

    @PostMapping("/{settlementId}/cancel")
    @Operation(summary = "Batalkan batch settlement",
            description = "Hanya batch PENDING yang dapat dibatalkan; periodenya lalu boleh dipakai ulang.")
    public ResponseEntity<SettlementResponse> cancel(
            @PathVariable UUID settlementId,
            @Valid @RequestBody(required = false) SettlementCancelRequest request) {

        return ResponseEntity.ok(settlementService.cancel(
                settlementId, request == null ? null : request.getReason()));
    }
}
