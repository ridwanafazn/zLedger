package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.PaymentProofDownload;
import com.alfagift.finledger.dto.PaymentProofPresignRequest;
import com.alfagift.finledger.dto.PaymentProofPresignResponse;
import com.alfagift.finledger.dto.PaymentProofResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Bukti bayar (Fase J): unggah, lihat, unduh, dan hapus berkas bukti transaksi.
 * <p>
 * Dua jalur unggah:
 * <ul>
 *   <li><b>multipart</b> — file kecil (≤ {@code app.storage.max-file-size-bytes}) lewat API,
 *       divalidasi sebelum disimpan;</li>
 *   <li><b>presigned</b> — file besar diunggah langsung ke object storage, lalu
 *       diverifikasi pada endpoint complete.</li>
 * </ul>
 * Byte file disimpan di {@code BlobStorage} (MinIO/S3/local); database hanya
 * menyimpan metadata + checksum SHA-256. Hanya uploader dan ADMIN yang boleh
 * mengakses berkas.
 */
public interface PaymentProofService {

    PaymentProofResponse upload(UUID transactionId, MultipartFile file, String uploaderId);

    /**
     * Membuat metadata PENDING + presigned PUT URL.
     *
     * @throws IllegalStateException bila storage tidak mendukung presigned upload
     */
    PaymentProofPresignResponse presign(PaymentProofPresignRequest request, String uploaderId);

    /**
     * Memverifikasi objek yang sudah diunggah (ukuran, tipe, checksum) dan
     * menandai metadata UPLOADED.
     */
    PaymentProofResponse complete(UUID proofId, String requesterId, boolean admin);

    List<PaymentProofResponse> findByTransaction(UUID transactionId, String requesterId, boolean admin);

    PaymentProofResponse get(UUID proofId, String requesterId, boolean admin);

    PaymentProofDownload download(UUID proofId, String requesterId, boolean admin);

    /**
     * Menghapus metadata dan blob (ADMIN). Idempoten bila metadata sudah tidak ada.
     */
    void delete(UUID proofId);

    /**
     * Menghapus bukti bayar UPLOADED yang lebih tua dari {@code olderThanDays}
     * beserta objeknya, serta metadata PENDING yang kedaluwarsa.
     *
     * @return jumlah metadata yang dihapus
     */
    int purgeExpired(int olderThanDays);
}
