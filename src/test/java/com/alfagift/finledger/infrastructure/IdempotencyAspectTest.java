package com.alfagift.finledger.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alfagift.finledger.dto.AccountResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test untuk {@link IdempotencyAspect}.
 * <p>
 * Memverifikasi:
 * <ul>
 *   <li>Permintaan pertama dengan kunci idempotency unik boleh diproses</li>
 *   <li>Permintaan duplikat dengan kunci sama ditolak (409 Conflict)</li>
 *   <li>Kunci disimpan di Redis dengan TTL yang betul</li>
 *   <li>Jika method gagal, kunci idempotency dihapus dari Redis</li>
 *   <li>Tanpa kunci idempotency, pemeriksaan diabaikan</li>
 *   <li>Redis key menggunakan prefix {@code finledger:idempotency:}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyAspectTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IdempotencyAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new IdempotencyAspect(redisTemplate, new ObjectMapper());
        // JANGAN stub opsForValue di sini — hanya stub pada test yang memerlukannya,
        // untuk menghindari UnnecessaryStubbing pada test noIdempotencyKey.
    }

    @Test
    @DisplayName("Permintaan pertama dengan kunci unik harus boleh diproses dan simpan ke Redis")
    void uniqueIdempotencyKey_shouldProceedAndSaveToRedis() throws Throwable {
        // Given
        String uniqueKey = "idem-" + UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // When
        Object result = aspect.checkIdempotency(uniqueKey, () -> "proceed-result");

        // Then
        assertThat(result).isEqualTo("proceed-result");
        verify(valueOperations, times(1))
                .setIfAbsent(eq("finledger:idempotency:" + uniqueKey), eq(""), any(Duration.class));
    }

    @Test
    @DisplayName("Kunci duplikat harus ditolak dengan IdempotencyRejectedException")
    void duplicateIdempotencyKey_shouldRejectWithConflict() {
        // Given
        String duplicateKey = "idem-duplicate";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false); // Redis already has the key

        // When & Then
        assertThatThrownBy(() -> aspect.checkIdempotency(duplicateKey, () -> "proceed-result"))
                .isInstanceOf(IdempotencyRejectedException.class);
    }

    @Test
    @DisplayName("Jika method gagal, kunci idempotency harus dihapus dari Redis")
    void methodFailure_shouldDeleteRedisKey() throws Throwable {
        // Given
        String key = "idem-fail-test";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // When - simulate method throwing exception
        assertThatThrownBy(() -> aspect.checkIdempotency(key, () -> {
            throw new RuntimeException("Simulated failure");
        })).isInstanceOf(RuntimeException.class);

        // Then - Redis key should be deleted
        verify(redisTemplate, times(1)).delete("finledger:idempotency:" + key);
    }

    @Test
    @DisplayName("Tanpa kunci idempotency, pemeriksaan diabaikan dan method diproses")
    void noIdempotencyKey_shouldSkipCheckAndProceed() throws Throwable {
        // Given - blank key, no stubbing needed since Redis is never called

        // When
        Object result = aspect.checkIdempotency("", () -> "proceed-result");

        // Then
        assertThat(result).isEqualTo("proceed-result");
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis key harus menggunakan prefix finledger:idempotency:")
    void redisKeyShouldUseCorrectPrefix() throws Throwable {
        // Given
        String key = "prefix-test-key";
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // When
        aspect.checkIdempotency(key, () -> "result");

        // Then
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations, times(1)).setIfAbsent(keyCaptor.capture(), anyString(), any(Duration.class));
        assertThat(keyCaptor.getValue()).startsWith("finledger:idempotency:");
        assertThat(keyCaptor.getValue()).endsWith(key);
    }

    /* ------------------------------------------------------------------ */
    /* Sprint C: idempotency sejati                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Retry identik harus mengembalikan respons dari cache, bukan 409")
    void identicalRetry_shouldReturnCachedResponse() throws Throwable {
        // Given
        String key = "cache-key";
        String scope = "AccountServiceImpl.createAccount";
        String fingerprint = "fp-123";
        AccountResponse cached = AccountResponse.builder()
                .id(UUID.randomUUID())
                .ownerId("cust-001")
                .accountName("Dompet")
                .build();
        String json = new ObjectMapper().writeValueAsString(cached);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("finledger:idempotency:" + scope + ":" + key))
                .thenReturn(fingerprint + "\n" + json);

        // When
        Object result = aspect.execute(scope, key, 86400, fingerprint,
                AccountResponse.class, () -> {
                    throw new IllegalStateException("method tidak boleh dijalankan ulang");
                });

        // Then
        assertThat(result).isInstanceOf(AccountResponse.class);
        assertThat(((AccountResponse) result).getOwnerId()).isEqualTo("cust-001");
    }

    @Test
    @DisplayName("Payload berbeda dengan kunci sama harus ditolak 409")
    void differentPayload_shouldBeRejected() {
        // Given
        String key = "conflict-key";
        String scope = "AccountServiceImpl.createAccount";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("finledger:idempotency:" + scope + ":" + key))
                .thenReturn("fingerprint-lain");

        // When & Then
        assertThatThrownBy(() -> aspect.execute(scope, key, 86400, "fingerprint-baru",
                AccountResponse.class, () -> "result"))
                .isInstanceOf(IdempotencyRejectedException.class)
                .hasMessageContaining("sudah digunakan");
    }

    @Test
    @DisplayName("Permintaan yang masih diproses harus 409 agar klien retry nanti")
    void inProgressRequest_shouldBeRejected() {
        // Given
        String key = "in-progress-key";
        String scope = "AccountServiceImpl.createAccount";
        String fingerprint = "fp-abc";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("finledger:idempotency:" + scope + ":" + key))
                .thenReturn(fingerprint);

        // When & Then
        assertThatThrownBy(() -> aspect.execute(scope, key, 86400, fingerprint,
                AccountResponse.class, () -> "result"))
                .isInstanceOf(IdempotencyRejectedException.class)
                .hasMessageContaining("sedang diproses");
    }

    @Test
    @DisplayName("Kunci Redis harus di-scope per operasi (Class.method)")
    void redisKeyShouldBeScopedPerOperation() throws Throwable {
        // Given
        String key = "scoped-key";
        String scope = "AccountServiceImpl.updateStatus";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        // When
        aspect.execute(scope, key, 86400, "fp", null, () -> "result");

        // Then
        verify(valueOperations).setIfAbsent(
                eq("finledger:idempotency:" + scope + ":" + key), eq("fp"), any(Duration.class));
    }

    @Test
    @DisplayName("Respons sukses harus disimpan ke Redis bersama fingerprint")
    void successfulResponse_shouldBeCached() throws Throwable {
        // Given
        String key = "cache-store-key";
        String scope = "AccountServiceImpl.createAccount";
        String fingerprint = "fp-store";

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        AccountResponse response = AccountResponse.builder().ownerId("cust-009").build();

        // When
        Object result = aspect.execute(scope, key, 86400, fingerprint,
                AccountResponse.class, () -> response);

        // Then
        assertThat(result).isSameAs(response);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq("finledger:idempotency:" + scope + ":" + key),
                valueCaptor.capture(),
                any(Duration.class));
        assertThat(valueCaptor.getValue()).startsWith(fingerprint + "\n");
        assertThat(valueCaptor.getValue()).contains("cust-009");
    }
}