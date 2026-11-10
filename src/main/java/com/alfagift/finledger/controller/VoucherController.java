package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.VoucherCreateRequest;
import com.alfagift.finledger.dto.VoucherRedeemRequest;
import com.alfagift.finledger.dto.VoucherRedemptionResponse;
import com.alfagift.finledger.dto.VoucherResponse;
import com.alfagift.finledger.dto.VoucherStatusRequest;
import com.alfagift.finledger.service.VoucherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Katalog voucher dan redeem flash sale.
 */
@RestController
@RequestMapping("/api/v1/vouchers")
@RequiredArgsConstructor
@Tag(name = "Voucher", description = "Katalog voucher, kuota, dan redeem")
public class VoucherController {

    private final VoucherService voucherService;

    @PostMapping
    @Operation(summary = "Buat voucher", description = "Membuat voucher baru berstatus DRAFT (ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Voucher dibuat"),
            @ApiResponse(responseCode = "400", description = "Kode duplikat / periode tidak valid")
    })
    public ResponseEntity<VoucherResponse> create(@Valid @RequestBody VoucherCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(voucherService.create(request));
    }

    @GetMapping
    @Operation(summary = "Cari voucher", description = "Daftar voucher dengan filter status/aktif dan pagination.")
    public ResponseEntity<PageResponse<VoucherResponse>> search(
            @Parameter(description = "Filter status (DRAFT/ACTIVE/INACTIVE/EXPIRED)")
            @RequestParam(required = false) String status,

            @Parameter(description = "Hanya tampilkan voucher yang sedang bisa diredeem")
            @RequestParam(required = false) Boolean activeOnly,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(voucherService.search(status, activeOnly, page, size));
    }

    @GetMapping("/{voucherId}")
    @Operation(summary = "Detail voucher")
    public ResponseEntity<VoucherResponse> get(@PathVariable UUID voucherId) {
        return ResponseEntity.ok(voucherService.get(voucherId));
    }

    @PatchMapping("/{voucherId}/status")
    @Operation(summary = "Ubah status voucher", description = "Mengaktifkan/menonaktifkan voucher (ADMIN).")
    public ResponseEntity<VoucherResponse> updateStatus(
            @PathVariable UUID voucherId,
            @Valid @RequestBody VoucherStatusRequest request) {

        return ResponseEntity.ok(voucherService.updateStatus(voucherId, request.getStatus()));
    }

    @PostMapping("/{voucherId}/redeem")
    @Operation(summary = "Redeem voucher",
            description = "Redeem voucher secara atomik: kuota dikurangi dan poin dipotong lewat ledger. " +
                    "Kunci idempotency wajib via header X-Idempotency-Key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Redeem berhasil"),
            @ApiResponse(responseCode = "400", description = "Voucher tidak aktif / kuota habis / poin kurang"),
            @ApiResponse(responseCode = "409", description = "Sudah pernah redeem / duplicate idempotency key")
    })
    public ResponseEntity<VoucherRedemptionResponse> redeem(
            @PathVariable UUID voucherId,
            @RequestBody(required = false) VoucherRedeemRequest request,
            @Parameter(hidden = true) @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            Authentication authentication) {

        String ownerId = (request != null && request.getOwnerId() != null && !request.getOwnerId().isBlank())
                ? request.getOwnerId()
                : authentication.getName();

        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));

        if (!admin && !ownerId.equals(authentication.getName())) {
            throw new AccessDeniedException("Hanya ADMIN yang boleh redeem untuk pemilik lain");
        }

        return ResponseEntity.ok(voucherService.redeem(voucherId, ownerId));
    }
}
