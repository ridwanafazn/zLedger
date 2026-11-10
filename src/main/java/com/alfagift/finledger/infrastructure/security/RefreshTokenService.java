package com.alfagift.finledger.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * Refresh token opaque disimpan di Redis.
 * <p>
 * Token dirotasi setiap kali dipakai (one-time use), sehingga token lama tidak
 * bisa dipakai ulang jika bocor.
 */
@Service
public class RefreshTokenService {

    private static final String PREFIX = "finledger:auth:refresh:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;

    public RefreshTokenService(StringRedisTemplate redisTemplate,
                               @Value("${app.security.jwt.refresh-ttl-days:7}") long refreshTtlDays) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = Duration.ofDays(refreshTtlDays).toSeconds();
    }

    public String issue(String username) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, username, Duration.ofSeconds(ttlSeconds));
        return token;
    }

    /**
     * Mengambil username lalu menghapus token (rotasi).
     *
     * @throws ResponseStatusException 401 bila token tidak dikenal/kedaluwarsa
     */
    public String consume(String token) {
        String username = redisTemplate.opsForValue().get(PREFIX + token);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token tidak valid atau kedaluwarsa");
        }
        redisTemplate.delete(PREFIX + token);
        return username;
    }

    public void revoke(String token) {
        redisTemplate.delete(PREFIX + token);
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
