package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.IdempotencyInspectionResponse;
import com.alfagift.finledger.dto.OutboxEventResponse;
import com.alfagift.finledger.dto.OutboxSummaryResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.service.AdminInspectionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint inspeksi read-only untuk kebutuhan demo dan audit.
 * <p>
 * Tidak mengubah state apa pun. Semua endpoint di bawah {@code /api/v1/admin/**}
 * dibatasi untuk role ADMIN oleh {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Inspeksi read-only: outbox Kafka dan cache idempotency")
public class AdminController {

    private final AdminInspectionService adminInspectionService;

    @GetMapping("/outbox")
    @Operation(
            summary = "Daftar event outbox",
            description = "Read-only. Menunjukkan event yang diantrekan untuk Kafka beserta status publikasinya."
    )
    public ResponseEntity<PageResponse<OutboxEventResponse>> listOutbox(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(adminInspectionService.listOutbox(status, page, size));
    }

    @GetMapping("/outbox/summary")
    @Operation(summary = "Ringkasan outbox", description = "Jumlah event outbox per status.")
    public ResponseEntity<OutboxSummaryResponse> outboxSummary() {
        return ResponseEntity.ok(adminInspectionService.outboxSummary());
    }

    @GetMapping("/idempotency")
    @Operation(
            summary = "Inspeksi cache idempotency",
            description = "Read-only. Menampilkan respons yang tersimpan di Redis untuk satu kunci idempotency."
    )
    public ResponseEntity<IdempotencyInspectionResponse> inspectIdempotency(
            @RequestParam String key) {

        return ResponseEntity.ok(adminInspectionService.inspectIdempotency(key));
    }
}
