package com.alfagift.finledger.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dilempar ketika username atau email sudah dipakai user lain.
 */
public class DuplicateUserException extends ResponseStatusException {

    public DuplicateUserException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
