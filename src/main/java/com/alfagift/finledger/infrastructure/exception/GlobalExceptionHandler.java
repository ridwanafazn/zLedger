package com.alfagift.finledger.infrastructure.exception;

import com.alfagift.finledger.infrastructure.IdempotencyRejectedException;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Penangani pengecualian global untuk semua REST controller.
 * <p>
 * Menjamin format respons error seragam dan tidak bocorkan stack trace
 * kepada client (production-ready).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Format respons error standard.
     */
    public record ErrorResponse(
            int status,
            String error,
            String message,
            LocalDateTime timestamp,
            String path
    ) {
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(
            InsufficientBalanceException ex, WebRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, "BALANCE_INSUFFICIENT", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyRejected(
            IdempotencyRejectedException ex, WebRequest request) {
        return buildError(HttpStatus.CONFLICT, "IDEMPOTENCY_REJECTED", ex.getMessage(), request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            EntityNotFoundException ex, WebRequest request) {
        return buildError(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.toList());
        return buildError(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                String.join("; ", errors), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(
            ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String error = ex.getReason() != null ? ex.getReason() : "ERROR";
        return buildError(status, error, ex.getReason(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, WebRequest request) {
        log.error("Kesalahan tak tertangani: ", ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR",
                "Terjadi kesalahan internal pada server", request);
    }

    private ResponseEntity<ErrorResponse> buildError(
            HttpStatus status, String error, String message, WebRequest request) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                error,
                message,
                LocalDateTime.now(),
                request.getDescription(false).replace("uri=", "")
        );
        return ResponseEntity.status(status).body(body);
    }
}