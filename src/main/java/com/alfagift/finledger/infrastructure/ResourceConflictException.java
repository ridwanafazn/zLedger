package com.alfagift.finledger.infrastructure;

/**
 * Permintaan bertentangan dengan state bisnis saat ini (periode settlement
 * bertumpuk, payout ganda, presigned upload kedaluwarsa, dsb.).
 * <p>
 * Dipetakan ke HTTP 409 Conflict: sintaks permintaan valid, tetapi state
 * resource tidak memungkinkan operasi tersebut.
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }
}
