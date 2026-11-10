package com.alfagift.finledger.domain.entities;

import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Satu eksekusi rekonsiliasi: membandingkan cached balance setiap akun dengan
 * saldo ekspektasi dari ledger ({@code opening_balance + CREDIT - DEBIT}) dan
 * memeriksa keseimbangan setiap jurnal.
 * <p>
 * Hasil disimpan agar dapat diaudit: siapa menjalankan, kapan, dan apa temuannya.
 */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = {"discrepancies"})
public class ReconciliationRun {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @EqualsAndHashCode.Include
    @Column(name = "id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private ReconciliationStatus status;

    @Column(name = "accounts_checked", nullable = false)
    @Builder.Default
    private int accountsChecked = 0;

    @Column(name = "discrepancy_count", nullable = false)
    @Builder.Default
    private int discrepancyCount = 0;

    @Column(name = "unbalanced_journal_count", nullable = false)
    @Builder.Default
    private int unbalancedJournalCount = 0;

    @Column(name = "total_abs_variance", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal totalAbsVariance = BigDecimal.ZERO;

    @Column(name = "triggered_by", length = 64)
    private String triggeredBy;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at", nullable = false)
    @Builder.Default
    private LocalDateTime completedAt = LocalDateTime.now();

    @Builder.Default
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();

    public void addDiscrepancy(ReconciliationDiscrepancy discrepancy) {
        discrepancies.add(discrepancy);
        discrepancy.setRun(this);
    }
}
