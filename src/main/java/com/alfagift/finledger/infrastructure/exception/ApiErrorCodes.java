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
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String MISSING_HEADER = "MISSING_HEADER";
    public static final String CONFLICT = "CONFLICT";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private ApiErrorCodes() {
    }
}