package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import com.alfagift.finledger.domain.enums.SettlementStatus;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.ReconciliationRunResponse;
import com.alfagift.finledger.dto.SettlementCreateRequest;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;
import com.alfagift.finledger.infrastructure.SettlementConflictException;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import com.alfagift.finledger.service.ReconciliationService;
import com.alfagift.finledger.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test Fase I: settlement + rekonsiliasi end-to-end di PostgreSQL.
 * <p>
 * Membuktikan alur nyata: checkout → refund → batch settlement → payout
 * (jurnal SETTLEMENT) → rekonsiliasi BALANCED, serta proteksi terhadap
 * periode bertumpuk dan payout ganda.
 */
@Testcontainers
@SpringBootTest
@Transactional
class SettlementIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_settlement")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private String suffix;
    private String merchantId;
    private String customerId;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        merchantId = "it-merchant-" + suffix;
        customerId = "it-customer-" + suffix;

        accountRepository.save(Account.builder()
                .ownerId(customerId)
                .accountName("IT Customer Wallet")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("500000.0000"))
                .openingBalance(new BigDecimal("500000.0000"))
                .build());

        accountRepository.save(Account.builder()
                .ownerId(merchantId)
                .accountName("IT Merchant Wallet")
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .balance(BigDecimal.ZERO.setScale(4))
                .openingBalance(BigDecimal.ZERO.setScale(4))
                .build());

        periodStart = LocalDateTime.now().minusHours(2);
        periodEnd = LocalDateTime.now().plusHours(2);
    }

    private UUID checkout(String amount, String idempotencyKey) {
        CheckoutResponse response = ledgerService.processMultiTenderPayment(CheckoutRequest.builder()
                .ownerId(customerId)
                .merchantId(merchantId)
                .totalAmount(new BigDecimal(amount))
                .currency(Currency.IDR)
                .idempotencyKey(idempotencyKey)
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal(amount))
                        .description("IT checkout " + idempotencyKey)
                        .build()))
                .build());
        return response.getTransactionId();
    }

    private SettlementCreateRequest settlementRequest() {
        return SettlementCreateRequest.builder()
                .merchantId(merchantId)
                .currency(Currency.IDR)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .build();
    }

    @Test
    @DisplayName("Alur lengkap: checkout + refund -> batch -> payout sekali -> rekonsiliasi BALANCED")
    void fullSettlementFlowShouldBeBalanced() {
        // Given: dua penjualan, satu di antaranya dibalik (refund)
        checkout("50000.00", "it-set-" + suffix + "-a");
        UUID refunded = checkout("10000.00", "it-set-" + suffix + "-b");
        ledgerService.reverseTransaction(refunded, "Barang dikembalikan");

        // When: preview
        SettlementPreviewResponse preview = settlementService.preview(
                merchantId, Currency.IDR, periodStart, periodEnd, null);

        // Then: gross 60000, refund 10000, fee 1% dari 50000 = 500, net 49500
        assertThat(preview.getGrossAmount()).isEqualByComparingTo("60000");
        assertThat(preview.getRefundAmount()).isEqualByComparingTo("10000");
        assertThat(preview.getFeeAmount()).isEqualByComparingTo("500");
        assertThat(preview.getNetAmount()).isEqualByComparingTo("49500");
        assertThat(preview.isPayable()).isTrue();

        // When: create batch
        SettlementResponse settlement = settlementService.create(settlementRequest(), "it-set-create-" + suffix);

        // Then: snapshot tersimpan dengan 3 item (2 PAYMENT + 1 REFUND)
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(settlement.getItemCount()).isEqualTo(3);
        assertThat(settlement.getNetAmount()).isEqualByComparingTo("49500");

        // Then: periode bertumpuk ditolak
        assertThatThrownBy(() -> settlementService.create(settlementRequest(), "it-set-overlap-" + suffix))
                .isInstanceOf(SettlementConflictException.class);

        // When: payout
        SettlementResponse paid = settlementService.pay(settlement.getId(), "it-set-pay-" + suffix);

        // Then: batch PAID dengan jurnal SETTLEMENT yang seimbang
        assertThat(paid.getStatus()).isEqualTo(SettlementStatus.PAID);
        assertThat(paid.getPayoutTransactionId()).isNotNull();

        JournalTransaction payout = journalTransactionRepository.findById(paid.getPayoutTransactionId())
                .orElseThrow();
        assertThat(payout.getReferenceType()).isEqualTo(ReferenceType.SETTLEMENT);

        List<LedgerEntry> payoutEntries = ledgerEntryRepository.findByJournalTransactionId(payout.getId());
        assertThat(payoutEntries).hasSize(2);

        BigDecimal debit = payoutEntries.stream()
                .filter(entry -> entry.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = payoutEntries.stream()
                .filter(entry -> entry.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit).isEqualByComparingTo("49500");

        // Then: saldo merchant tersisa 60000 - 10000 - 49500 = 500
        Account merchantAfter = accountRepository
                .findAccountByOwnerIdAndCurrencyCustom(merchantId, Currency.IDR).orElseThrow();
        assertThat(merchantAfter.getBalance()).isEqualByComparingTo("500");

        // Then: payout kedua ditolak
        assertThatThrownBy(() -> settlementService.pay(settlement.getId(), "it-set-pay-again-" + suffix))
                .isInstanceOf(SettlementConflictException.class);

        // Then: rekonsiliasi tetap seimbang setelah payout
        ReconciliationRunResponse run = reconciliationService.run("it-test", "setelah payout");
        assertThat(run.getStatus())
                .as("temuan: %s", run.getDiscrepancies())
                .isEqualTo(ReconciliationStatus.BALANCED);
        assertThat(run.getDiscrepancyCount()).isZero();
    }

    @Test
    @DisplayName("Batch yang dibatalkan membebaskan periodenya untuk batch baru")
    void cancelledSettlementShouldReleasePeriod() {
        checkout("20000.00", "it-cancel-" + suffix);

        SettlementResponse first = settlementService.create(settlementRequest(), "it-cancel-1-" + suffix);
        settlementService.cancel(first.getId(), "Salah input");

        SettlementResponse second = settlementService.create(settlementRequest(), "it-cancel-2-" + suffix);

        assertThat(second.getStatus()).isEqualTo(SettlementStatus.PENDING);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getNetAmount()).isEqualByComparingTo("19800");
    }

    @Test
    @DisplayName("Rekonsiliasi mendeteksi saldo akun yang menyimpang dari ledger")
    void reconciliationShouldDetectDriftedBalance() {
        Account drifted = accountRepository.save(Account.builder()
                .ownerId("it-drift-" + suffix)
                .accountName("IT Drifted Account")
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .balance(new BigDecimal("2000.0000"))
                .openingBalance(new BigDecimal("1000.0000"))
                .build());

        ReconciliationRunResponse run = reconciliationService.run("it-test", "audit selisih");

        assertThat(run.getStatus()).isEqualTo(ReconciliationStatus.DISCREPANCY);
        assertThat(run.getDiscrepancies())
                .anySatisfy(discrepancy -> {
                    assertThat(discrepancy.getAccountId()).isEqualTo(drifted.getId());
                    assertThat(discrepancy.getVariance()).isEqualByComparingTo("1000");
                    assertThat(discrepancy.getExpectedBalance()).isEqualByComparingTo("1000");
                    assertThat(discrepancy.getActualBalance()).isEqualByComparingTo("2000");
                });
    }
}
