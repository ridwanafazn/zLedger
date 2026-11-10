package com.alfagift.finledger.infrastructure.metrics;

import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrik bisnis FinLedger yang diekspos melalui {@code /actuator/prometheus}
 * (Fase K) dan siap dipakai dashboard Grafana.
 * <p>
 * Semua metrik diberi prefix {@code finledger.} agar mudah dibedakan dari
 * metrik HTTP/JVM bawaan Spring Boot.
 */
@Component
public class FinLedgerMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger lastReconciliationDiscrepancies = new AtomicInteger(0);

    public FinLedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("finledger.reconciliation.discrepancies.last", lastReconciliationDiscrepancies,
                        AtomicInteger::get)
                .description("Jumlah temuan pada eksekusi rekonsiliasi terakhir (0 = BALANCED)")
                .register(registry);
    }

    /* ------------------------------------------------------------------ */
    /* Settlement                                                         */
    /* ------------------------------------------------------------------ */

    public void settlementCreated(String currency, BigDecimal netAmount) {
        Counter.builder("finledger.settlement.created")
                .description("Jumlah batch settlement yang dibuat")
                .tag("currency", currency)
                .register(registry)
                .increment();
        amount("finledger.settlement.created.amount", currency, "Nilai net batch settlement yang dibuat", netAmount);
    }

    public void settlementPaid(String currency, BigDecimal netAmount) {
        Counter.builder("finledger.settlement.paid")
                .description("Jumlah payout settlement yang berhasil diposting")
                .tag("currency", currency)
                .register(registry)
                .increment();
        amount("finledger.settlement.payout.amount", currency, "Nilai net payout settlement", netAmount);
    }

    public void settlementCancelled(String currency) {
        Counter.builder("finledger.settlement.cancelled")
                .description("Jumlah batch settlement yang dibatalkan")
                .tag("currency", currency)
                .register(registry)
                .increment();
    }

    public void settlementRejected(String reason) {
        Counter.builder("finledger.settlement.rejected")
                .description("Jumlah permintaan settlement yang ditolak")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /* ------------------------------------------------------------------ */
    /* Rekonsiliasi                                                       */
    /* ------------------------------------------------------------------ */

    public void reconciliationCompleted(ReconciliationStatus status, int discrepancyCount, long durationMillis) {
        lastReconciliationDiscrepancies.set(discrepancyCount);

        Counter.builder("finledger.reconciliation.runs")
                .description("Jumlah eksekusi rekonsiliasi")
                .tag("status", status.name())
                .register(registry)
                .increment();

        DistributionSummary.builder("finledger.reconciliation.discrepancies")
                .description("Jumlah temuan per eksekusi rekonsiliasi")
                .tag("status", status.name())
                .register(registry)
                .record(discrepancyCount);

        Timer.builder("finledger.reconciliation.duration")
                .description("Durasi eksekusi rekonsiliasi")
                .register(registry)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------------ */
    /* Bukti bayar                                                        */
    /* ------------------------------------------------------------------ */

    public void proofUploaded(String contentType, long sizeBytes) {
        Counter.builder("finledger.payment_proof.uploaded")
                .description("Jumlah bukti bayar yang berhasil diunggah")
                .tag("content_type", contentType)
                .register(registry)
                .increment();

        DistributionSummary.builder("finledger.payment_proof.size.bytes")
                .description("Ukuran file bukti bayar")
                .baseUnit("bytes")
                .register(registry)
                .record(sizeBytes);
    }

    public void proofRejected(String reason) {
        Counter.builder("finledger.payment_proof.rejected")
                .description("Jumlah unggahan bukti bayar yang ditolak")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void proofPresigned(String contentType) {
        Counter.builder("finledger.payment_proof.presigned")
                .description("Jumlah presigned upload yang diterbitkan")
                .tag("content_type", contentType)
                .register(registry)
                .increment();
    }

    public void proofPurged(int count) {
        Counter.builder("finledger.payment_proof.purged")
                .description("Jumlah bukti bayar yang dihapus oleh kebijakan retensi")
                .register(registry)
                .increment(count);
    }

    private void amount(String name, String currency, String description, BigDecimal value) {
        DistributionSummary.builder(name)
                .description(description)
                .baseUnit("currency")
                .tag("currency", currency)
                .register(registry)
                .record(value == null ? 0 : value.doubleValue());
    }
}
