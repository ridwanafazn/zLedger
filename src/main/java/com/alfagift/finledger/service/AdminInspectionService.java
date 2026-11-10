package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.IdempotencyInspectionResponse;
import com.alfagift.finledger.dto.OutboxEventResponse;
import com.alfagift.finledger.dto.OutboxSummaryResponse;
import com.alfagift.finledger.dto.PageResponse;

/**
 * Inspeksi read-only untuk kebutuhan demo dan audit.
 */
public interface AdminInspectionService {

    PageResponse<OutboxEventResponse> listOutbox(String status, int page, int size);

    OutboxSummaryResponse outboxSummary();

    IdempotencyInspectionResponse inspectIdempotency(String key);
}
