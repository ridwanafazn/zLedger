package com.alfagift.finledger.domain.enums;

/**
 * Cara berkas bukti bayar masuk ke object storage.
 */
public enum UploadMethod {
    /** Klien mengirim multipart ke API; server memvalidasi lalu menyimpan. */
    MULTIPART,
    /** Klien mengunggah langsung ke object storage memakai presigned PUT. */
    PRESIGNED
}
