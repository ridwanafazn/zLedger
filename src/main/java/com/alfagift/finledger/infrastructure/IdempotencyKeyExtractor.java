package com.alfagift.finledger.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Utiliti untuk mengekstrak kunci idempotency dari permintaan HTTP terkini.
 */
public final class IdempotencyKeyExtractor {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";

    private IdempotencyKeyExtractor() {
    }

    public static String extract() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            String key = request.getHeader(IDEMPOTENCY_HEADER);
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return null;
    }
}