package com.alfagift.finledger.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dilempar apabila permintaan duplikat ditolak oleh aspek idempotency.
 */
public class IdempotencyRejectedException extends ResponseStatusException {

    public IdempotencyRejectedException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}