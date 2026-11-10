package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.service.LedgerService;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test konkurensi (Sprint B).
 * <p>
 * Membuktikan pessimistic locking benar-benar bekerja: dua transaksi paralel
 * yang masing-masing membutuhkan 150.000 dari saldo 200.000 tidak boleh
 * sama-sama berhasil (oversell) dan saldo tidak boleh negatif.
 * <p>
 * Dijalankan oleh maven-failsafe pada {@code mvn verify} (butuh Docker).
 */
@Testcontainers
@SpringBootTest
class LedgerConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_concurrency")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    private String customerOwner;
    private String merchantOwner;
    private UUID customerAccountId;

    @BeforeEach
    void setUp() {
        // Owner unik per test agar tidak bentrok dengan unique constraint (owner_id, currency)
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        customerOwner = "concurrency-cust-" + suffix;
        merchantOwner = "concurrency-merch-" + suffix;

        Account customer = accountRepository.save(Account.builder()
                .ownerId(customerOwner)
                .accountName("Concurrency Customer - IDR")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("200000.00"))
                .build());
        customerAccountId = customer.getId();

        accountRepository.save(Account.builder()
                .ownerId(merchantOwner)
                .accountName("Concurrency Merchant - IDR")
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .balance(BigDecimal.ZERO)
                .build());
    }

    @Test
    @DisplayName("Concurrency: dua checkout paralel tidak boleh oversell dan saldo tidak negatif")
    void concurrentCheckoutsShouldNotOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            Future<Boolean> first = pool.submit(() -> attemptCheckout("concurrency-idem-a", startGate));
            Future<Boolean> second = pool.submit(() -> attemptCheckout("concurrency-idem-b", startGate));

            startGate.countDown();

            int successCount = (first.get(30, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

            // Hanya satu yang boleh berhasil (200.000 hanya cukup untuk satu transaksi 150.000)
            assertThat(successCount).isEqualTo(1);

            Account customerAfter = accountRepository.findById(customerAccountId).orElseThrow();
            assertThat(customerAfter.getBalance())
                    .isGreaterThanOrEqualTo(BigDecimal.ZERO)
                    .isEqualByComparingTo("50000.00");

            Account merchantAfter = accountRepository
                    .findAccountByOwnerIdAndCurrencyCustom(merchantOwner, Currency.IDR)
                    .orElseThrow();
            assertThat(merchantAfter.getBalance()).isEqualByComparingTo("150000.00");
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean attemptCheckout(String idempotencyKey, CountDownLatch startGate) {
        try {
            startGate.await(10, TimeUnit.SECONDS);
            ledgerService.processMultiTenderPayment(CheckoutRequest.builder()
                    .ownerId(customerOwner)
                    .merchantId(merchantOwner)
                    .totalAmount(new BigDecimal("150000.00"))
                    .currency(Currency.IDR)
                    .idempotencyKey(idempotencyKey)
                    .tenders(List.of(TenderDetail.builder()
                            .currency(Currency.IDR)
                            .amount(new BigDecimal("150000.00"))
                            .description("Concurrent checkout")
                            .build()))
                    .build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
