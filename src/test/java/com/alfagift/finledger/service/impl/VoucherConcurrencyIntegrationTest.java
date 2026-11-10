package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.Voucher;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.VoucherStatus;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.VoucherRedemptionRepository;
import com.alfagift.finledger.repository.VoucherRepository;
import com.alfagift.finledger.service.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test anti-overselling voucher (Fase G).
 * <p>
 * 20 user berebut voucher berkuota 5 secara paralel: harus tepat 5 yang sukses,
 * sisa kuota 0, dan jumlah jurnal redeem = 5.
 */
@Testcontainers
@SpringBootTest
class VoucherConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_voucher")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private VoucherRedemptionRepository voucherRedemptionRepository;

    @Autowired
    private AccountRepository accountRepository;

    private static final int TOTAL_USERS = 20;
    private static final int QUOTA = 5;

    private final List<String> owners = new ArrayList<>();
    private UUID voucherId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        for (int i = 0; i < TOTAL_USERS; i++) {
            String owner = "vc-" + suffix + "-" + i;
            owners.add(owner);
            accountRepository.save(Account.builder()
                    .ownerId(owner)
                    .accountName("Voucher wallet " + i)
                    .accountType(AccountType.LIABILITY)
                    .currency(Currency.A_POIN)
                    .balance(new BigDecimal("10000"))
                    .build());
        }

        Voucher voucher = voucherRepository.save(Voucher.builder()
                .code("CONC-" + suffix)
                .name("Concurrency voucher")
                .pointsCost(new BigDecimal("100"))
                .totalQuota(QUOTA)
                .remainingQuota(QUOTA)
                .startsAt(LocalDateTime.now().minusMinutes(1))
                .endsAt(LocalDateTime.now().plusHours(1))
                .status(VoucherStatus.ACTIVE)
                .build());
        voucherId = voucher.getId();
    }

    @Test
    @DisplayName("Kuota voucher tidak boleh oversell saat direbut paralel")
    void parallelRedeemShouldNotOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(TOTAL_USERS);
        CountDownLatch gate = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (String owner : owners) {
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    try {
                        voucherService.redeem(voucherId, owner);
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                }));
            }

            gate.countDown();

            int success = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    success++;
                }
            }

            assertThat(success).isEqualTo(QUOTA);

            Voucher after = voucherRepository.findById(voucherId).orElseThrow();
            assertThat(after.getRemainingQuota()).isZero();
            assertThat(voucherRedemptionRepository.count()).isGreaterThanOrEqualTo(QUOTA);
        } finally {
            pool.shutdownNow();
        }
    }
}
