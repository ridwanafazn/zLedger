package com.alfagift.finledger.infrastructure.storage;

/**
 * Penyimpanan blob gagal (I/O, kredensial, bucket tidak tersedia).
 * <p>
 * Dipetakan ke HTTP 503 agar klien tahu masalahnya di sisi infrastruktur,
 * bukan pada payload.
 */
public class BlobStorageException extends RuntimeException {

    public BlobStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
