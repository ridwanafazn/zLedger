package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.OutboxEvent;
import com.alfagift.finledger.domain.enums.OutboxStatus;
import com.alfagift.finledger.dto.IdempotencyInspectionResponse;
import com.alfagift.finledger.dto.OutboxEventResponse;
import com.alfagift.finledger.dto.OutboxSummaryResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.repository.OutboxEventRepository;
import com.alfagift.finledger.service.AdminInspectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Implementasi inspeksi read-only: outbox Kafka dan cache idempotency Redis.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminInspectionServiceImpl implements AdminInspectionService {

    private static final String IDEMPOTENCY_PREFIX = "finledger:idempotency:";

    private final OutboxEventRepository outboxEventRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public PageResponse<OutboxEventResponse> listOutbox(String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<OutboxEvent> result;
        if (status != null && !status.isBlank()) {
            result = outboxEventRepository.findByStatus(parseStatus(status), pageable);
        } else {
            result = outboxEventRepository.findAll(pageable);
        }

        List<OutboxEventResponse> content = result.getContent().stream()
                .map(this::toResponse)
                .toList();

        return PageResponse.<OutboxEventResponse>builder()
                .content(content)
                .page(PageResponse.PageMetadata.builder()
                        .number(result.getNumber())
                        .size(result.getSize())
                        .totalElements(result.getTotalElements())
                        .totalPages(result.getTotalPages())
                        .first(result.isFirst())
                        .last(result.isLast())
                        .numberOfElements(result.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    public OutboxSummaryResponse outboxSummary() {
        long pending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
        long published = outboxEventRepository.countByStatus(OutboxStatus.PUBLISHED);
        long failed = outboxEventRepository.countByStatus(OutboxStatus.FAILED);

        return OutboxSummaryResponse.builder()
                .pending(pending)
                .published(published)
                .failed(failed)
                .total(pending + published + failed)
                .build();
    }

    @Override
    public IdempotencyInspectionResponse inspectIdempotency(String key) {
        String pattern = IDEMPOTENCY_PREFIX + "*:" + key;
        List<IdempotencyInspectionResponse.Entry> entries = new ArrayList<>();

        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                String redisKey = cursor.next();
                String value = redisTemplate.opsForValue().get(redisKey);
                Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);

                String fingerprint = null;
                Object cachedResponse = null;
                if (value != null) {
                    int separator = value.indexOf('\n');
                    if (separator >= 0) {
                        fingerprint = value.substring(0, separator);
                        String json = value.substring(separator + 1);
                        if (!json.isBlank()) {
                            cachedResponse = parseJson(json);
                        }
                    } else {
                        fingerprint = value;
                    }
                }

                entries.add(IdempotencyInspectionResponse.Entry.builder()
                        .redisKey(redisKey)
                        .scope(extractScope(redisKey))
                        .ttlSeconds(ttl == null ? -1 : ttl)
                        .fingerprint(fingerprint)
                        .cachedResponse(cachedResponse)
                        .build());
            }
        }

        return IdempotencyInspectionResponse.builder()
                .key(key)
                .entries(entries)
                .build();
    }

    private OutboxStatus parseStatus(String raw) {
        try {
            return OutboxStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Status outbox tidak valid: " + raw);
        }
    }

    private OutboxEventResponse toResponse(OutboxEvent event) {
        return OutboxEventResponse.builder()
                .id(event.getId())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .eventType(event.getEventType())
                .status(event.getStatus().name())
                .attempts(event.getAttempts())
                .lastError(event.getLastError())
                .createdAt(event.getCreatedAt())
                .publishedAt(event.getPublishedAt())
                .payload(event.getPayload())
                .build();
    }

    private String extractScope(String redisKey) {
        if (!redisKey.startsWith(IDEMPOTENCY_PREFIX)) {
            return null;
        }
        String rest = redisKey.substring(IDEMPOTENCY_PREFIX.length());
        int lastColon = rest.lastIndexOf(':');
        return lastColon > 0 ? rest.substring(0, lastColon) : null;
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            return json;
        }
    }
}
