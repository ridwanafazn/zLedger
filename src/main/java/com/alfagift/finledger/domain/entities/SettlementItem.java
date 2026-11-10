package com.alfagift.finledger.domain.entities;

import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Satu jurnal ledger yang ikut membentuk nilai batch settlement.
 * <p>
 * {@code signedAmount} bernilai positif untuk PAYMENT (credit merchant) dan
 * negatif untuk REFUND (debit merchant), sehingga {@code gross + refundSigned}
 * selalu konsisten dengan snapshot batch.
 */
@Entity
@Table(name = "settlement_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_items_transaction",
                columnNames = {"settlement_id", "transaction_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"settlement"})
public class SettlementItem {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settlement_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_settlement_items_settlement"))
    private MerchantSettlement settlement;

    @Column(name = "transaction_id", nullable = false, columnDefinition = "UUID")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reference_type", nullable = false)
    private ReferenceType referenceType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "signed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal signedAmount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
