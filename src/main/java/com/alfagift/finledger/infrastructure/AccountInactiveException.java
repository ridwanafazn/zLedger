package com.alfagift.finledger.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dilempar apabila akaun tidak berstatus ACTIVE (SUSPENDED/CLOSED) tetapi
 * masih digunakan dalam transaksi yang mengubah saldo.
 */
public class AccountInactiveException extends ResponseStatusException {

    public AccountInactiveException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
