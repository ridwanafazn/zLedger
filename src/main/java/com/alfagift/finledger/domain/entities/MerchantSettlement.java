package com.alfagift.finledger.domain.entities;

import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.SettlementStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batch settlement pendapatan merchant untuk satu periode.
 * <p>
 * Nilai batch bersifat <b>snapshot</b>: dihitung sekali saat dibuat dan tidak
 * berubah setelahnya. Payout memindahkan {@code netAmount} dari akun merchant
 * ke akun clearing sistem melalui jurnal double-entry {@code SETTLEMENT}.
 */
@Entity
@Table(name = "merchant_settlements",
        uniqueConstraints = @UniqueConstraint(name = "uk_merchant_settlements_idempotency",
                columnNames = "idempotency_key"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"items"})
public class MerchantSettlement {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "currency", nullable = false)
    private Currency currency;

    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(name = "fee_percent", nullable = false, precision = 7, scale = 4)
    private BigDecimal feePercent;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SettlementStatus status = SettlementStatus.PENDING;

    @Column(name = "payout_transaction_id", columnDefinition = "UUID")
    private UUID payoutTransactionId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Builder.Default
    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SettlementItem> items = new ArrayList<>();

    /**
     * Menambahkan satu rincian jurnal ke batch ini.
     */
    public void addItem(SettlementItem item) {
        items.add(item);
        item.setSettlement(this);
    }

    /**
     * Batch hanya boleh dibayar bila masih PENDING dan bernilai positif.
     */
    public boolean isPayable() {
        return status == SettlementStatus.PENDING && netAmount.signum() > 0;
    }

    /**
     * Batch hanya boleh dibatalkan selama belum dibayar.
     */
    public boolean isCancellable() {
        return status == SettlementStatus.PENDING;
    }
}
