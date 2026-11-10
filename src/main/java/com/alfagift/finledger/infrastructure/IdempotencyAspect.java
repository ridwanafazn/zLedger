package com.alfagift.finledger.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * AOP aspect untuk idempotency berbasis Redis.
 * <p>
 * Semantik yang diterapkan:
 * <ol>
 *   <li><b>Request pertama</b> dengan kunci unik diproses, lalu respons sukses
 *       disimpan sebagai cache bersama fingerprint payload.</li>
 *   <li><b>Retry identik</b> (kunci sama, payload sama) mengembalikan respons
 *       yang tersimpan — bukan 409 — sehingga klien mobile aman mengulang.</li>
 *   <li><b>Kunci sama dengan payload berbeda</b> ditolak {@code 409 Conflict}.</li>
 *   <li><b>Kunci yang masih diproses</b> (belum ada respons) juga 409 agar klien retry nanti.</li>
 *   <li><b>Method gagal</b> menghapus kunci sehingga klien boleh mencoba lagi.</li>
 * </ol>
 * Kunci di-scope per operasi ({@code Class.method}) untuk mencegah tabrakan
 * ketika klien memakai kunci yang sama pada endpoint berbeda.
 * <p>
 * Aspect dijalankan <b>di luar</b> transaksi database agar kegagalan saat commit
 * juga menghapus kunci.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final String REDIS_PREFIX = "finledger:idempotency:";
    private static final long DEFAULT_TTL_SECONDS = 86_400;
    private static final String SEPARATOR = "\n";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Intercept method yang di-annotasi {@link Idempotent}.
     */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String idempotencyKey = resolveIdempotencyKey(idempotent);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String scope = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        String fingerprint = fingerprint(joinPoint.getArgs());

        return execute(scope, idempotencyKey, idempotent.ttlSeconds(), fingerprint,
                signature.getReturnType(), joinPoint::proceed);
    }

    /**
     * Varian legacy tanpa scoping/caching (dipakai uji unit level rendah).
     */
    public Object checkIdempotency(String key, ThrowingSupplier proceed) throws Throwable {
        return checkIdempotency(key, DEFAULT_TTL_SECONDS, proceed);
    }

    public Object checkIdempotency(String key, long ttlSeconds, ThrowingSupplier proceed) throws Throwable {
        return execute(null, key, ttlSeconds, "", null, proceed);
    }

    /**
     * Inti idempotency: acquire kunci, cache respons, dan layani retry.
     *
     * @param scope       identitas operasi (mis. {@code AccountServiceImpl.createAccount}); null untuk legacy
     * @param key         nilai header {@code X-Idempotency-Key}
     * @param ttlSeconds  masa hidup kunci
     * @param fingerprint hash payload request
     * @param returnType  tipe respons untuk deserialisasi cache (null = tidak cache)
     * @param proceed     aksi bisnis
     */
    public Object execute(String scope, String key, long ttlSeconds, String fingerprint,
                          Class<?> returnType, ThrowingSupplier proceed) throws Throwable {
        if (key == null || key.isBlank()) {
            return proceed.get();
        }

        String redisKey = redisKey(scope, key);
        String storedFingerprint = fingerprint == null ? "" : fingerprint;

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, storedFingerprint, Duration.ofSeconds(ttlSeconds));

        if (Boolean.TRUE.equals(acquired)) {
            try {
                Object result = proceed.get();
                cacheResponse(redisKey, storedFingerprint, result, returnType, ttlSeconds);
                return result;
            } catch (Throwable ex) {
                // Gagal: hapus kunci agar klien boleh mencoba lagi
                redisTemplate.delete(redisKey);
                throw ex;
            }
        }

        String stored = redisTemplate.opsForValue().get(redisKey);

        if (stored != null && stored.startsWith(storedFingerprint + SEPARATOR)) {
            String cachedJson = stored.substring((storedFingerprint + SEPARATOR).length());
            if (returnType != null && !cachedJson.isBlank()) {
                log.info("Mengembalikan respons idempotent dari cache untuk key={}", key);
                return objectMapper.readValue(cachedJson, returnType);
            }
        }

        if (stored != null && storedFingerprint.equals(stored)) {
            throw new IdempotencyRejectedException(
                    "Permintaan dengan kunci " + key + " sedang diproses. Silakan coba lagi.");
        }

        log.warn("Duplicate idempotency key rejected (payload berbeda): {}", key);
        throw new IdempotencyRejectedException(
                "Kunci idempotency " + key + " sudah digunakan untuk permintaan lain");
    }

    private void cacheResponse(String redisKey, String fingerprint, Object result,
                               Class<?> returnType, long ttlSeconds) {
        if (returnType == null || result == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            redisTemplate.opsForValue()
                    .set(redisKey, fingerprint + SEPARATOR + json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            log.warn("Gagal menyimpan cache respons idempotent: {}", ex.getMessage());
        }
    }

    private String redisKey(String scope, String key) {
        if (scope == null || scope.isBlank()) {
            return REDIS_PREFIX + key;
        }
        return REDIS_PREFIX + scope + ":" + key;
    }

    /**
     * Hash SHA-256 dari argumen method, sehingga retry dengan payload berbeda
     * dapat dibedakan dari retry identik.
     */
    private String fingerprint(Object[] args) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(args);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (Exception ex) {
            log.warn("Fingerprint payload gagal dibuat, fallback ke hashCode: {}", ex.getMessage());
            return Integer.toHexString(java.util.Arrays.deepHashCode(args));
        }
    }

    /**
     * Functional interface yang membenarkan checked/throwable exception.
     */
    @FunctionalInterface
    public interface ThrowingSupplier {
        Object get() throws Throwable;
    }

    private String resolveIdempotencyKey(Idempotent idempotent) {
        if (!idempotent.key().isBlank()) {
            return idempotent.key();
        }

        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes sra) {
            HttpServletRequest request = sra.getRequest();
            String header = request.getHeader(IDEMPOTENCY_HEADER);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }

        return null;
    }
}
