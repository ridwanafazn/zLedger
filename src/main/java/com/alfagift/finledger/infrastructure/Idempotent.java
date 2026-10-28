package com.alfagift.finledger.infrastructure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation custom untuk idempotency pada endpoint REST.
 * <p>
 * Diproses oleh {@link IdempotencyAspect} menggunakan Redis.
 * Permintaan yang membawa {@code X-Idempotency-Key} yang sama dalam
 * tempoh TTL akan ditolak untuk mencegah pemprosesan ganda.
 *
 * <p>Contoh penggunaan:</p>
 * <pre>{@code
 * @PostMapping("/checkout")
 * @Idempotent
 * public ResponseEntity<?> checkout(@RequestHeader("X-Idempotency-Key") String key, ...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * Nama kunci dalam Redis. Jika kosong, header {@code X-Idempotency-Key} akan digunakan.
     */
    String key() default "";

    /**
     * Tempoh hidup kunci idempotency dalam saat (default: 24 jam).
     */
    long ttlSeconds() default 86400;
}