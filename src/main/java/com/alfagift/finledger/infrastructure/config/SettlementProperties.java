package com.alfagift.finledger.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * Konfigurasi modul settlement merchant (Fase I).
 *
 * @param feePercent      persentase fee default per batch, mis. {@code 1.0} untuk 1%
 * @param clearingOwnerId owner id akun sistem yang menerima payout merchant
 */
@ConfigurationProperties(prefix = "app.settlement")
public record SettlementProperties(
        @DefaultValue("1.0") BigDecimal feePercent,
        @DefaultValue("system-settlement") String clearingOwnerId) {

    public SettlementProperties {
        if (feePercent == null) {
            feePercent = new BigDecimal("1.0");
        }
        if (feePercent.signum() < 0 || feePercent.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException(
                    "app.settlement.fee-percent harus bernilai 0..100, diterima: " + feePercent);
        }
        if (clearingOwnerId == null || clearingOwnerId.isBlank()) {
            clearingOwnerId = "system-settlement";
        }
    }
}
