package com.alfagift.finledger.infrastructure.exception;

/**
 * Kod error standard untuk API FinLedger Engine.
 * Digunakan untuk konsistensi respons error di seluruh sistem.
 */
public class ApiErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String BALANCE_INSUFFICIENT = "BALANCE_INSUFFICIENT";
    public static final String IDEMPOTENCY_REJECTED = "IDEMPOTENCY_REJECTED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR";

    private ApiErrorCodes() {
    }
}