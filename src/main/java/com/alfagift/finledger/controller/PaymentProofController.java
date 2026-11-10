package com.alfagift.finledger.controller;

import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofResponse;
import com.alfagift.finledger.service.PaymentProofService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Bukti bayar transaksi: unggah multipart, lihat metadata, unduh, dan hapus (ADMIN).
 */
@RestController
@RequestMapping("/api/v1/payment-proofs")
@RequiredArgsConstructor
@Tag(name = "Payment Proof", description = "Unggah dan unduh bukti bayar transaksi")
public class PaymentProofController {

    private final PaymentProofService paymentProofService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Unggah bukti bayar",
            description = "Menyimpan file bukti (PNG/JPEG/WEBP/PDF, maksimum sesuai konfigurasi) untuk satu "
                    + "transaksi. Isi file divalidasi lewat magic bytes dan disimpan dengan checksum SHA-256.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bukti bayar tersimpan"),
            @ApiResponse(responseCode = "400", description = "Tipe/ukuran file tidak valid atau duplikat"),
            @ApiResponse(responseCode = "404", description = "Transaksi tidak ditemukan")
    })
    public ResponseEntity<PaymentProofResponse> upload(
            @Parameter(description = "ID transaksi ledger yang dibuktikan")
            @RequestParam UUID transactionId,
            @Parameter(description = "File bukti bayar", content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
            @RequestPart("file") MultipartFile file,
            Authentication authentication) {

        PaymentProofResponse response = paymentProofService.upload(
                transactionId, file, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "Daftar bukti bayar per transaksi",
            description = "ADMIN melihat semua; user biasa hanya melihat unggahannya sendiri.")
    public ResponseEntity<List<PaymentProofResponse>> list(
            @RequestParam UUID transactionId,
            Authentication authentication) {

        return ResponseEntity.ok(paymentProofService.findByTransaction(
                transactionId, authentication.getName(), isAdmin(authentication)));
    }

    @GetMapping("/{proofId}")
    @Operation(summary = "Metadata bukti bayar")
    public ResponseEntity<PaymentProofResponse> get(@PathVariable UUID proofId,
                                                    Authentication authentication) {
        return ResponseEntity.ok(paymentProofService.get(
                proofId, authentication.getName(), isAdmin(authentication)));
    }

    @GetMapping("/{proofId}/content")
    @Operation(summary = "Unduh isi bukti bayar",
            description = "Streaming isi file melalui aplikasi; tersedia untuk uploader dan ADMIN.")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID proofId,
                                                       Authentication authentication) {
        PaymentProofDownload download = paymentProofService.download(
                proofId, authentication.getName(), isAdmin(authentication));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(download.contentType()));
        headers.setContentLength(download.sizeBytes());
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + download.filename().replace("\"", "") + "\"");
        headers.setCacheControl("private, max-age=60");

        return new ResponseEntity<>(new InputStreamResource(download.content()), headers, HttpStatus.OK);
    }

    @DeleteMapping("/{proofId}")
    @Operation(summary = "Hapus bukti bayar", description = "Menghapus metadata dan blob (ADMIN).")
    public ResponseEntity<Void> delete(@PathVariable UUID proofId) {
        paymentProofService.delete(proofId);
        return ResponseEntity.noContent().build();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
