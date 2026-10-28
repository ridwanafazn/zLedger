package com.alfagift.finledger.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dilempar apabila balans akaun tidak mencukupi untuk melaksanakan transaksi.
 */
public class InsufficientBalanceException extends ResponseStatusException {

    public InsufficientBalanceException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}