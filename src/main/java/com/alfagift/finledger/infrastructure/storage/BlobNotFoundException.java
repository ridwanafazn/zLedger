package com.alfagift.finledger.infrastructure.storage;

/**
 * Objek blob tidak ditemukan di storage.
 */
public class BlobNotFoundException extends RuntimeException {

    public BlobNotFoundException(String key) {
        super("Objek blob tidak ditemukan: " + key);
    }
}
