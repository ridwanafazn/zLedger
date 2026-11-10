package com.alfagift.finledger.infrastructure.metrics;

import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test untuk {@link FinLedgerMetrics} dengan {@link SimpleMeterRegistry}.
 */
class FinLedgerMetricsTest {

    private SimpleMeterRegistry registry;
    private FinLedgerMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new FinLedgerMetrics(registry);
    }

    @Test
    @DisplayName("Settlement created/paid/cancelled/rejected tercatat dengan tag yang benar")
    void shouldRecordSettlementMetrics() {
        metrics.settlementCreated("IDR", new BigDecimal("891.0000"));
        metrics.settlementPaid("IDR", new BigDecimal("891.0000"));
        metrics.settlementCancelled("IDR");
        metrics.settlementRejected("overlap");

        assertThat(registry.get("finledger.settlement.created").tag("currency", "IDR").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.settlement.paid").tag("currency", "IDR").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.settlement.cancelled").tag("currency", "IDR").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.settlement.rejected").tag("reason", "overlap").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.settlement.payout.amount").tag("currency", "IDR").summary().totalAmount())
                .isEqualTo(891.0);
        assertThat(registry.get("finledger.settlement.created.amount").tag("currency", "IDR").summary().count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Rekonsiliasi mencatat status, jumlah temuan, dan durasi")
    void shouldRecordReconciliationMetrics() {
        metrics.reconciliationCompleted(ReconciliationStatus.DISCREPANCY, 3, 42);
        metrics.reconciliationCompleted(ReconciliationStatus.BALANCED, 0, 7);

        assertThat(registry.get("finledger.reconciliation.runs").tag("status", "DISCREPANCY").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.reconciliation.runs").tag("status", "BALANCED").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("finledger.reconciliation.discrepancies").tag("status", "DISCREPANCY")
                .summary().max()).isEqualTo(3.0);
        assertThat(registry.get("finledger.reconciliation.duration").timer().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Bukti bayar mencatat jumlah unggah, ukuran, dan penolakan")
    void shouldRecordPaymentProofMetrics() {
        metrics.proofUploaded("image/png", 2048);
        metrics.proofRejected("signature");

        assertThat(registry.get("finledger.payment_proof.uploaded").tag("content_type", "image/png")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("finledger.payment_proof.size.bytes").summary().totalAmount()).isEqualTo(2048.0);
        assertThat(registry.get("finledger.payment_proof.rejected").tag("reason", "signature")
                .counter().count()).isEqualTo(1.0);
    }
}
