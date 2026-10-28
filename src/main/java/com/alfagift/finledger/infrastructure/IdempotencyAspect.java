package com.alfagift.finledger.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * AOP aspect untuk melaksanakan idempotency menggunakan Redis.
 * <p>
 * Setiap method yang di-annotasi dengan {@link Idempotent} akan memeriksa
 * Redis untuk kunci idempotency. Jika kunci sudah wujud, permintaan ditolak
 * dengan status {@code 409 Conflict}. Jika belum, kunci akan disimpan
 * dengan TTL dan method akan dilaksanakan.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyAspect {

    private static final String IDEMPOTENCY_HEADER = "X-Idempotency-Key";
    private static final String REDIS_PREFIX = "finledger:idempotency:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Intercept method yang di-annotasi {@link Idempotent}.
     */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String idempotencyKey = resolveIdempotencyKey(idempotent);
        return checkIdempotency(idempotencyKey, joinPoint::proceed);
    }

    /**
     * Method publik untuk ujian unit — memeriksa kunci idempotency tanpa AOP proxy.
     * <p>
     * Jika kunci baru, simpan di Redis dan lanjutkan. Jika duplikat, lempar
     * {@link IdempotencyRejectedException}. Jika method gagal, hapus kunci.
     */
    public Object checkIdempotency(String key, ThrowingSupplier proceed) throws Throwable {
        if (key == null || key.isBlank()) {
            // Tiada kunci idempotency — lalusi pemeriksaan
            return proceed.get();
        }

        String redisKey = REDIS_PREFIX + key;
        Boolean setIfAbsent = redisTemplate.opsForValue().setIfAbsent(redisKey, "1",
                java.time.Duration.ofSeconds(86400));

        if (Boolean.FALSE.equals(setIfAbsent)) {
            log.warn("Duplicate idempotency key rejected: {}", key);
            throw new IdempotencyRejectedException("Permintaan duplikat dengan kunci: " + key);
        }

        try {
            return proceed.get();
        } catch (Throwable ex) {
            // Hapus kunci jika method gagal supaya klien boleh cuba lagi
            redisTemplate.delete(redisKey);
            throw ex;
        }
    }

    /**
     * Functional interface yang membenarkan checked/throwable exception.
     * Digunakan supaya {@link ProceedingJoinPoint#proceed()} boleh diteruskan
     * tanpa kesilapan kompilasi.
     */
    @FunctionalInterface
    public interface ThrowingSupplier {
        Object get() throws Throwable;
    }

    private String resolveIdempotencyKey(Idempotent idempotent) {
        // Jika annotation menentukan key secara eksplik, gunakan itu
        if (!idempotent.key().isBlank()) {
            return idempotent.key();
        }

        // Ambil dari header HTTP X-Idempotency-Key
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