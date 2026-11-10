package com.alfagift.finledger.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ringkasan jumlah event outbox per status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxSummaryResponse {

    private long pending;
    private long published;
    private long failed;
    private long total;
}
