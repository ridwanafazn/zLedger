package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test untuk {@link LedgerServiceImpl} dengan Testcontainers.
 * <p>
 * Menggunakan PostgreSQL sebenarnya melalui container Docker untuk memverifikasi
 * integritas double-entry di level basis data. Redis disediakan melalui docker-compose
 * dan diperolehi automatik oleh Spring Boot {@code @ServiceConnection}.
 */
@Testcontainers
@SpringBootTest
class LedgerServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finledger_test")
            .withUsername("finledger")
            .withPassword("finledger_test_secret");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("kafka.topic.points-awarded", () -> "loyalty-points-awarded-test");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JournalTransactionRepository journalTransactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    private Account customerAccount;
    private Account merchantAccount;

    @BeforeEach
    void setUp() {
        customerAccount = Account.builder()
                .ownerId("integration-cust-001")
                .accountName("Integration Customer Wallet - IDR")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("200000.00"))
                .build();
        customerAccount = accountRepository.save(customerAccount);

        merchantAccount = Account.builder()
                .ownerId("integration-merch-001")
                .accountName("Integration Merchant Wallet - IDR")
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .balance(new BigDecimal("1000000.00"))
                .build();
        merchantAccount = accountRepository.save(merchantAccount);
    }

    @Test
    @DisplayName("Integration: Harus simpan transaksi double-entry ke basis data dengan DEBIT = CREDIT")
    void shouldPersistDoubleEntryTransactionToDatabase() {
        // Given
        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("integration-cust-001")
                .merchantId("integration-merch-001")
                .totalAmount(new BigDecimal("75000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("integration-idem-001")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("75000.00"))
                        .description("Integration test pembelian")
                        .build()))
                .build();

        // When
        CheckoutResponse response = ledgerService.processMultiTenderPayment(request);

        // Then - Response
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(response.getBalanceRemaining()).isEqualByComparingTo(new BigDecimal("125000.00"));

        // Then - Database: Journal transaction exists
        UUID transactionId = response.getTransactionId();
        JournalTransaction journal = journalTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new AssertionError("Journal transaction not found"));
        assertThat(journal.getIdempotencyKey()).isEqualTo("integration-idem-001");
        assertThat(journal.getReferenceType()).isEqualTo(ReferenceType.PAYMENT);

        // Then - Database: Ledger entries exactly 2, debit = credit
        List<LedgerEntry> entries = ledgerEntryRepository.findByJournalTransactionId(transactionId);
        assertThat(entries).hasSize(2);

        BigDecimal totalDebit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo(new BigDecimal("75000.00"));

        // Then - Account balances updated in database
        Account updatedCustomer = accountRepository.findById(customerAccount.getId()).orElseThrow();
        Account updatedMerchant = accountRepository.findById(merchantAccount.getId()).orElseThrow();

        assertThat(updatedCustomer.getBalance()).isEqualByComparingTo(new BigDecimal("125000.00"));
        assertThat(updatedMerchant.getBalance()).isEqualByComparingTo(new BigDecimal("1075000.00"));
    }

    @Test
    @DisplayName("Integration: Duplicate idempotency key harus ditolak oleh basis data (unique constraint)")
    void duplicateIdempotencyKeyShouldBeRejectedByDatabase() {
        // Given - first transaction succeeds
        CheckoutRequest request1 = CheckoutRequest.builder()
                .ownerId("integration-cust-001")
                .merchantId("integration-merch-001")
                .totalAmount(new BigDecimal("10000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("integration-duplicate-idem")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("10000.00"))
                        .description("First transaction")
                        .build()))
                .build();

        ledgerService.processMultiTenderPayment(request1);

        // When & Then - second request with same idempotency key should fail
        CheckoutRequest request2 = CheckoutRequest.builder()
                .ownerId("integration-cust-001")
                .merchantId("integration-merch-001")
                .totalAmount(new BigDecimal("10000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("integration-duplicate-idem")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("10000.00"))
                        .description("Duplicate transaction")
                        .build()))
                .build();

        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request2))
                .isInstanceOf(RuntimeException.class); // unique constraint violation or application-level
    }

    @Test
    @DisplayName("Integration: Insufficient balance harus melempar exception dan tidak mengubah basis data")
    void insufficientBalanceShouldThrowAndNotModifyDatabase() {
        // Given
        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("integration-cust-001")
                .merchantId("integration-merch-001")
                .totalAmount(new BigDecimal("999999999.00")) // more than balance
                .currency(Currency.IDR)
                .idempotencyKey("integration-insufficient-idem")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("999999999.00"))
                        .description("Impossible purchase")
                        .build()))
                .build();

        BigDecimal customerBalanceBefore = customerAccount.getBalance();
        BigDecimal merchantBalanceBefore = merchantAccount.getBalance();

        // When & Then
        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(InsufficientBalanceException.class);

        // Then - No journal saved
        long journalCount = journalTransactionRepository.count();
        assertThat(journalCount).isZero();

        // Then - Account balances unchanged
        Account unchangedCustomer = accountRepository.findById(customerAccount.getId()).orElseThrow();
        Account unchangedMerchant = accountRepository.findById(merchantAccount.getId()).orElseThrow();

        assertThat(unchangedCustomer.getBalance()).isEqualByComparingTo(customerBalanceBefore);
        assertThat(unchangedMerchant.getBalance()).isEqualByComparingTo(merchantBalanceBefore);
    }

    @Test
    @DisplayName("Integration: Harus ambil mutasi akaun dari basis data")
    void shouldRetrieveAccountMutationsFromDatabase() {
        // Given - create a transaction first
        CheckoutRequest request = CheckoutRequest.builder()
                .ownerId("integration-cust-001")
                .merchantId("integration-merch-001")
                .totalAmount(new BigDecimal("25000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("integration-mutation-idem")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("25000.00"))
                        .description("Mutation test")
                        .build()))
                .build();

        CheckoutResponse response = ledgerService.processMultiTenderPayment(request);

        // When
        List<LedgerEntry> mutations = ledgerService.getAccountMutations(customerAccount.getId());

        // Then
        assertThat(mutations).isNotEmpty();
        assertThat(mutations.get(0).getJournalTransaction().getId()).isEqualTo(response.getTransactionId());
        assertThat(mutations.get(0).getEntryType()).isEqualTo(EntryType.DEBIT);
    }
}