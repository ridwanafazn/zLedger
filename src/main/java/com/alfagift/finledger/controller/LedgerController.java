package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST API untuk FinLedger Engine.
 * <p>
 * Semua endpoint dilengkapi dokumentasi OpenAPI 3 (Swagger UI) dengan
 * contoh request/response dan respons error standard.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Operasi ledger double-entry: pembayaran, tebasan, dan mutasi")
public class LedgerController {

    private final LedgerService ledgerService;

    /**
     * Pembayaran multi-tender (campuran IDR Balance + A_Poin).
     * <p>
     * Memerlukan header {@code X-Idempotency-Key} untuk idempotency.
     */
    @Operation(
            summary = "Proses pembayaran multi-tender",
            description = "Proses pembayaran item dengan campuran IDR Balance dan A_Poin. " +
                    "Menerapkan double-entry ledger dan idempotency melalui X-Idempotency-Key."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Transaksi berhasil",
                    content = @Content(schema = @Schema(implementation = CheckoutResponse.class),
                            examples = @ExampleObject(
                                    name = "Checkout Berhasil",
                                    value = """
                                            {
                                              "transactionId": "550e8400-e29b-41d4-a716-446655440000",
                                              "ownerId": "cust-001",
                                              "merchantId": "merch-001",
                                              "currency": "IDR",
                                              "totalAmount": 100000.00,
                                              "balanceRemaining": 900000.00,
                                              "processedAt": "2026-10-28T10:00:00",
                                              "status": "SUCCESS"
                                            }
                                            """))),
            @ApiResponse(responseCode = "400", description = "Balans tidak mencukupi / validasi gagal"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key"),
            @ApiResponse(responseCode = "500", description = "Kesalahan server internal")
    })
    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(
            @Parameter(hidden = true) @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        // Pastikan idempotency key dari header digunakan jika kosong di body
        if (request.getIdempotencyKey() == null && idempotencyKey != null) {
            request.setIdempotencyKey(idempotencyKey);
        }
        CheckoutResponse response = ledgerService.processMultiTenderPayment(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Flash sale redemption — tebaskan voucher terbatas dengan A_Poin.
     */
    @Operation(
            summary = "Tebasan flash sale",
            description = "Tebaskan voucher terbatas menggunakan A_Poin dengan kunci idempotency."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Tebasan berhasil",
                    content = @Content(schema = @Schema(implementation = CheckoutResponse.class))),
            @ApiResponse(responseCode = "400", description = "Poin tidak mencukupi"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key")
    })
    @PostMapping("/redeem")
    public ResponseEntity<CheckoutResponse> redeem(
            @RequestParam @NotBlank String ownerId,
            @RequestParam @NotNull UUID voucherId,
            @RequestParam @NotNull Integer points,
            @RequestParam @NotBlank String idempotencyKey) {
        CheckoutResponse response = ledgerService.redeemFlashSale(ownerId, voucherId, points, idempotencyKey);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    /**
     * Lihat semua mutasi (ledger entries) bagi akaun tertentu.
     */
    @Operation(
            summary = "Lihat mutasi akaun",
            description = "Ambil semua entri ledger (mutasi) bagi suatu akaun, urutan terbaru dulu."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Senarai mutasi akaun",
                    content = @Content(schema = @Schema(implementation = LedgerEntry.class))),
            @ApiResponse(responseCode = "404", description = "Akaun tidak dijumpai")
    })
    @GetMapping("/accounts/{accountId}/mutations")
    public ResponseEntity<List<LedgerEntry>> getMutations(
            @Parameter(description = "ID akaun", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID accountId) {
        List<LedgerEntry> mutations = ledgerService.getAccountMutations(accountId);
        return ResponseEntity.ok(mutations);
    }
}