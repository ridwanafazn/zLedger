package com.alfagift.finledger.infrastructure.job;

import com.alfagift.finledger.infrastructure.storage.StorageProperties;
import com.alfagift.finledger.service.PaymentProofService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kebijakan retensi bukti bayar (Fase J).
 * <p>
 * Setiap hari job ini menghapus:
 * <ul>
 *   <li>bukti {@code UPLOADED} yang lebih tua dari {@code app.storage.retention-days};</li>
 *   <li>metadata {@code PENDING} yang presigned URL-nya sudah kedaluwarsa
 *       (klien tidak pernah menyelesaikan unggahan).</li>
 * </ul>
 * Objek di storage ikut dihapus agar tidak ada byte yatim.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentProofRetentionJob {

    private final PaymentProofService paymentProofService;
    private final StorageProperties storageProperties;

    @Scheduled(cron = "${app.storage.retention-cron:0 0 3 * * *}")
    public void purgeExpiredProofs() {
        try {
            int purged = paymentProofService.purgeExpired(storageProperties.retentionDays());
            if (purged > 0) {
                log.info("Job retensi bukti bayar menghapus {} objek (batas {} hari)",
                        purged, storageProperties.retentionDays());
            }
        } catch (Exception ex) {
            // Job terjadwal tidak boleh mematikan scheduler; kegagalan dicatat untuk investigasi.
            log.error("Job retensi bukti bayar gagal: {}", ex.getMessage(), ex);
        }
    }
}
