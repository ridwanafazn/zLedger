package com.alfagift.finledger.domain.events;

import com.alfagift.finledger.domain.enums.Currency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payload dikirim melalui Kafka topik {@code loyalty-points-awarded}
 * selepas pelanggan successfully bertambah A_Poin.
 * <p>
 * Consumer akan simulate pengiriman push notification atau log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointsAwardedEvent {

    private String transactionId;
    private String ownerId;
    private Currency currency;
    private BigDecimal amount;
    private LocalDateTime awardedAt;
    private String description;
}