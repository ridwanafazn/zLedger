package com.alfagift.finledger.controller;

import com.alfagift.finledger.domain.enums.UserRole;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.UserCreateRequest;
import com.alfagift.finledger.dto.UserResponse;
import com.alfagift.finledger.dto.UserStatusRequest;
import com.alfagift.finledger.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.UUID;

/**
 * Manajemen user (khusus ADMIN).
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User", description = "Manajemen user aplikasi (ADMIN)")
public class UserController {

    private final UserService userService;

    @PostMapping
    @Operation(summary = "Buat user", description = "Membuat user dengan role tertentu (ADMIN).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User dibuat"),
            @ApiResponse(responseCode = "409", description = "Username/email sudah dipakai")
    })
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request,
            @Parameter(hidden = true) @RequestHeader("X-Idempotency-Key") String idempotencyKey) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.create(request, idempotencyKey));
    }

    @GetMapping
    @Operation(summary = "Cari user", description = "Daftar user dengan filter role/status/teks + pagination (ADMIN).")
    public ResponseEntity<PageResponse<UserResponse>> search(
            @Parameter(description = "Filter role (ADMIN/MERCHANT/CUSTOMER)")
            @RequestParam(required = false) String role,

            @Parameter(description = "Filter status aktif")
            @RequestParam(required = false) Boolean enabled,

            @Parameter(description = "Pencarian pada username/email/nama")
            @RequestParam(required = false) String q,

            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(userService.search(parseRole(role), enabled, q, page, size));
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Detail user")
    public ResponseEntity<UserResponse> get(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.getById(userId));
    }

    @PatchMapping("/{userId}/status")
    @Operation(summary = "Aktifkan/nonaktifkan user",
            description = "Menonaktifkan user akan langsung mencabut refresh token dan access token aktifnya.")
    public ResponseEntity<UserResponse> updateStatus(
            @PathVariable UUID userId,
            @Valid @RequestBody UserStatusRequest request,
            @Parameter(hidden = true) Authentication authentication) {

        return ResponseEntity.ok(
                userService.updateStatus(userId, request.getEnabled(), authentication.getName()));
    }

    private UserRole parseRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(String.format(
                    "Nilai role tidak valid: '%s'. Pilihan: %s",
                    raw, Arrays.toString(UserRole.values())));
        }
    }
}
