package com.alfagift.finledger.infrastructure.exception;

import com.alfagift.finledger.infrastructure.IdempotencyRejectedException;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Penangani pengecualian global dengan format <b>RFC 7807 Problem Details</b>.
 * <p>
 * Semua error dikembalikan sebagai {@code application/problem+json} dengan field
 * standar {@code type, title, status, detail, instance} plus ekstensi
 * {@code errorCode, timestamp, errors} agar klien mobile dapat menangani error
 * secara deterministik tanpa mem-parsing string.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String TYPE_BASE = "https://api.finledger.com/errors/";

    /**
     * Representasi RFC 7807 dengan ekstensi proyek.
     */
    public record ProblemDetailResponse(
            String type,
            String title,
            int status,
            String detail,
            String instance,
            String errorCode,
            LocalDateTime timestamp,
            List<String> errors
    ) {
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ProblemDetailResponse> handleInsufficientBalance(
            InsufficientBalanceException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Insufficient Balance",
                ApiErrorCodes.BALANCE_INSUFFICIENT, ex.getReason(), null, request);
    }

    @ExceptionHandler(IdempotencyRejectedException.class)
    public ResponseEntity<ProblemDetailResponse> handleIdempotencyRejected(
            IdempotencyRejectedException ex, WebRequest request) {
        return build(HttpStatus.CONFLICT, "Idempotency Conflict",
                ApiErrorCodes.IDEMPOTENCY_REJECTED, ex.getReason(), null, request);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetailResponse> handleNotFound(
            EntityNotFoundException ex, WebRequest request) {
        return build(HttpStatus.NOT_FOUND, "Resource Not Found",
                ApiErrorCodes.NOT_FOUND, ex.getMessage(), null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetailResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Pelanggaran constraint basis data: {}", ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "Data Conflict",
                ApiErrorCodes.CONFLICT,
                "Permintaan bertentangan dengan data yang sudah ada (kemungkinan duplikat)",
                null, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetailResponse> handleIllegalArgument(
            IllegalArgumentException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid Request",
                ApiErrorCodes.INVALID_REQUEST, ex.getMessage(), null, request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetailResponse> handleMissingHeader(
            MissingRequestHeaderException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Missing Required Header",
                ApiErrorCodes.MISSING_HEADER,
                "Header wajib tidak ditemukan: " + ex.getHeaderName(), null, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetailResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed Request Body",
                ApiErrorCodes.INVALID_REQUEST,
                "Body request tidak dapat dibaca atau formatnya tidak valid", null, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetailResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid Request Parameter",
                ApiErrorCodes.INVALID_REQUEST,
                "Nilai parameter '" + ex.getName() + "' tidak valid: " + ex.getValue(), null, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetailResponse> handleAccessDenied(
            AccessDeniedException ex, WebRequest request) {
        return build(HttpStatus.FORBIDDEN, "Forbidden",
                ApiErrorCodes.FORBIDDEN, ex.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetailResponse> handleValidation(
            MethodArgumentNotValidException ex, WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation Failed",
                ApiErrorCodes.VALIDATION_ERROR, "Satu atau lebih field tidak valid", errors, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetailResponse> handleResponseStatus(
            ResponseStatusException ex, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String errorCode = switch (status) {
            case NOT_FOUND -> ApiErrorCodes.NOT_FOUND;
            case CONFLICT -> ApiErrorCodes.CONFLICT;
            case BAD_REQUEST -> ApiErrorCodes.INVALID_REQUEST;
            case UNAUTHORIZED -> ApiErrorCodes.UNAUTHORIZED;
            case FORBIDDEN -> ApiErrorCodes.FORBIDDEN;
            default -> status.is5xxServerError()
                    ? ApiErrorCodes.INTERNAL_SERVER_ERROR : ApiErrorCodes.INVALID_REQUEST;
        };
        return build(status, status.getReasonPhrase(), errorCode,
                ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetailResponse> handleGeneric(Exception ex, WebRequest request) {
        log.error("Kesalahan tak tertangani: ", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                ApiErrorCodes.INTERNAL_SERVER_ERROR,
                "Terjadi kesalahan internal pada server", null, request);
    }

    private ResponseEntity<ProblemDetailResponse> build(
            HttpStatus status,
            String title,
            String errorCode,
            String detail,
            List<String> errors,
            WebRequest request) {

        ProblemDetailResponse body = new ProblemDetailResponse(
                TYPE_BASE + errorCode.toLowerCase().replace('_', '-'),
                title,
                status.value(),
                detail,
                request.getDescription(false).replace("uri=", ""),
                errorCode,
                LocalDateTime.now(),
                errors
        );

        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }
}
