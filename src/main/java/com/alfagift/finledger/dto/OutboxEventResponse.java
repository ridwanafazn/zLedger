package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Representasi read-only satu event outbox untuk inspeksi admin.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEventResponse {

    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String status;
    private int attempts;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    private String payload;
}
