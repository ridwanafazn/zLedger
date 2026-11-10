package com.alfagift.finledger.domain.enums;

/**
 * Status metadata bukti bayar.
 * <ul>
 *   <li>PENDING — metadata dibuat, objek belum diunggah (presigned upload)</li>
 *   <li>UPLOADED — objek sudah diverifikasi (ukuran, tipe, checksum)</li>
 * </ul>
 */
public enum PaymentProofStatus {
    PENDING,
    UPLOADED
}
