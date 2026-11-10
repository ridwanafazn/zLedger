package com.alfagift.finledger.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Daftar pencabutan access token berbasis Redis.
 * <p>
 * JWT bersifat stateless, sehingga token yang sudah diterbitkan tetap valid
 * sampai kedaluwarsa. Saat user dinonaktifkan, username dimasukkan ke daftar
 * cabut dengan TTL sama dengan umur token; filter JWT menolak token tersebut.
 * <p>
 * Redis dipilih agar pengecekan tetap O(1) dan tidak membebani database.
 */
@Service
@Slf4j
public class TokenRevocationService {

    private static final String PREFIX = "finledger:auth:revoked:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public TokenRevocationService(StringRedisTemplate redisTemplate,
                                  @Value("${app.security.jwt.expiry-minutes:60}") long expiryMinutes) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = expiryMinutes * 60;
    }

    public void revoke(String username) {
        redisTemplate.opsForValue().set(PREFIX + username, "1", Duration.ofSeconds(ttlSeconds));
    }

    public void clear(String username) {
        redisTemplate.delete(PREFIX + username);
    }

    /**
     * Gagal-terbuka (fail-open) bila Redis bermasalah: API tetap melayani request,
     * dan kejadiannya dicatat agar bisa ditindaklanjuti.
     */
    public boolean isRevoked(String username) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(PREFIX + username));
        } catch (RuntimeException ex) {
            log.warn("Gagal memeriksa status pencabutan token untuk '{}': {}", username, ex.getMessage());
            return false;
        }
    }
}
