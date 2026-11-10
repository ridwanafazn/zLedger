package com.alfagift.finledger.domain.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Temuan tunggal pada satu run rekonsiliasi.
 * <p>
 * Dua jenis temuan:
 * <ul>
 *   <li>{@code BALANCE_MISMATCH} — cached balance akun ≠ opening + CREDIT − DEBIT</li>
 *   <li>{@code UNBALANCED_JOURNAL} — jumlah DEBIT ≠ jumlah CREDIT pada satu jurnal</li>
 * </ul>
 */
@Entity
@Table(name = "reconciliation_discrepancies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"run"})
public class ReconciliationDiscrepancy {

    public static final String TYPE_BALANCE_MISMATCH = "BALANCE_MISMATCH";
    public static final String TYPE_UNBALANCED_JOURNAL = "UNBALANCED_JOURNAL";

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_reconciliation_discrepancies_run"))
    private ReconciliationRun run;

    @Column(name = "account_id", columnDefinition = "UUID")
    private UUID accountId;

    @Column(name = "discrepancy_type", nullable = false, length = 32)
    private String discrepancyType;

    @Column(name = "expected_balance", precision = 19, scale = 4)
    private BigDecimal expectedBalance;

    @Column(name = "actual_balance", precision = 19, scale = 4)
    private BigDecimal actualBalance;

    @Column(name = "variance", precision = 19, scale = 4)
    private BigDecimal variance;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
