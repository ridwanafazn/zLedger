package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Bukti bayar (Fase J): unggah, lihat, unduh, dan hapus berkas bukti transaksi.
 * <p>
 * Byte file disimpan di {@code BlobStorage} (MinIO/S3/local); database hanya
 * menyimpan metadata + checksum SHA-256. Hanya uploader dan ADMIN yang boleh
 * mengakses berkas.
 */
public interface PaymentProofService {

    PaymentProofResponse upload(UUID transactionId, MultipartFile file, String uploaderId);

    List<PaymentProofResponse> findByTransaction(UUID transactionId, String requesterId, boolean admin);

    PaymentProofResponse get(UUID proofId, String requesterId, boolean admin);

    PaymentProofDownload download(UUID proofId, String requesterId, boolean admin);

    /**
     * Menghapus metadata dan blob (ADMIN). Idempoten bila metadata sudah tidak ada.
     */
    void delete(UUID proofId);
}
