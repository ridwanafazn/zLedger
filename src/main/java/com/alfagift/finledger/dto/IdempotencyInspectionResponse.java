package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Hasil inspeksi cache idempotency di Redis untuk satu kunci.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyInspectionResponse {

    private String key;
    private List<Entry> entries;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private String redisKey;
        private String scope;
        private long ttlSeconds;
        private String fingerprint;
        private Object cachedResponse;
    }
}
