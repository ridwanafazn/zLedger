package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.AccountCreateRequest;
import com.alfagift.finledger.dto.AccountResponse;
import com.alfagift.finledger.dto.AccountStatusRequest;
import com.alfagift.finledger.dto.BalanceResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Controller untuk manajemen akun ledger.
 */
@RestController
@RequestMapping("/api/v1/ledger/accounts")
@RequiredArgsConstructor
@Tag(name = "Account", description = "Manajemen akun: create, balance, status, mutations")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @Operation(
            summary = "Buat akun baru",
            description = "Membuat akun ledger baru untuk pelanggan/merchant/system. " +
                    "Kunci idempotency wajib dikirim via header X-Idempotency-Key."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Akun berhasil dibuat",
                    content = @Content(schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validasi gagal / akun sudah ada",
                    content = @Content(schema = @Schema(implementation = com.alfagift.finledger.infrastructure.exception.ApiErrorCodes.class))),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key",
                    content = @Content(schema = @Schema(implementation = com.alfagift.finledger.infrastructure.exception.ApiErrorCodes.class)))
    })
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody AccountCreateRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        AccountResponse response = accountService.createAccount(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{accountId}")
    @Operation(
            summary = "Detail akun",
            description = "Mengambil detail lengkap akun berdasarkan ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Detail akun",
                    content = @Content(schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "404", description = "Akun tidak ditemukan")
    })
    public ResponseEntity<AccountResponse> getAccount(
            @Parameter(description = "ID akun", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID accountId) {

        AccountResponse response = accountService.getAccount(accountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}/balance")
    @Operation(
            summary = "Inquiry saldo akun",
            description = "Mengambil saldo terkini akun (lightweight, tanpa lock). " +
                    "Cocok untuk polling atau tampilan real-time."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saldo akun",
                    content = @Content(schema = @Schema(implementation = BalanceResponse.class))),
            @ApiResponse(responseCode = "404", description = "Akun tidak ditemukan")
    })
    public ResponseEntity<BalanceResponse> getBalance(
            @Parameter(description = "ID akun", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID accountId) {

        BalanceResponse response = accountService.getBalance(accountId);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{accountId}/status")
    @Operation(
            summary = "Ubah status akun",
            description = "Mengubah status akun (ACTIVE/SUSPENDED/CLOSED). " +
                    "Transisi legal: ACTIVE <-> SUSPENDED, ACTIVE/SUSPENDED -> CLOSED, CLOSED -> ACTIVE (reopen). " +
                    "Kunci idempotency wajib via header X-Idempotency-Key."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status berhasil diubah",
                    content = @Content(schema = @Schema(implementation = AccountResponse.class))),
            @ApiResponse(responseCode = "400", description = "Transisi status tidak valid",
                    content = @Content(schema = @Schema(implementation = com.alfagift.finledger.infrastructure.exception.ApiErrorCodes.class))),
            @ApiResponse(responseCode = "404", description = "Akun tidak ditemukan"),
            @ApiResponse(responseCode = "409", description = "Duplicate idempotency key")
    })
    public ResponseEntity<AccountResponse> updateStatus(
            @Parameter(description = "ID akun", example = "550e8400-e29b-41d4-a716-446655440000")
            @PathVariable UUID accountId,
            @Valid @RequestBody AccountStatusRequest request,
            @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        AccountResponse response = accountService.updateStatus(accountId, request, idempotencyKey);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(
            summary = "Cari akun (paginated)",
            description = "Mencari akun dengan filter dan pagination. " +
                    "Sortable fields: createdAt, updatedAt, balance, accountName, ownerId, currency, status. " +
                    "Default sort: createdAt desc. Max size: 100."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Daftar akun",
                    content = @Content(schema = @Schema(implementation = PageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Parameter tidak valid")
    })
    public ResponseEntity<PageResponse<AccountResponse>> searchAccounts(
            @Parameter(description = "Filter Owner ID")
            @RequestParam(required = false) String ownerId,

            @Parameter(description = "Filter Currency (IDR/A_POIN)")
            @RequestParam(required = false) String currency,

            @Parameter(description = "Filter Account Type (ASSET/LIABILITY/REVENUE/EXPENSE)")
            @RequestParam(required = false) String accountType,

            @Parameter(description = "Filter Status (ACTIVE/SUSPENDED/CLOSED)")
            @RequestParam(required = false) String status,

            @Parameter(description = "Page number (0-based)", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (max 100)", example = "20")
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,

            @Parameter(description = "Sort field,direction (e.g. createdAt,desc)")
            @RequestParam(required = false) String sort) {

        PageResponse<AccountResponse> response = accountService.searchAccounts(
                ownerId, currency, accountType, status, page, size, sort);
        return ResponseEntity.ok(response);
    }
}