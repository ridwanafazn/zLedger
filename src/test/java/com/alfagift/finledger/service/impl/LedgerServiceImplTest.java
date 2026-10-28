package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.domain.events.PointsAwardedEvent;
import com.alfagift.finledger.dto.CheckoutRequest;
import com.alfagift.finledger.dto.CheckoutResponse;
import com.alfagift.finledger.dto.CheckoutRequest.TenderDetail;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.kafka.LoyaltyPointsProducer;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit test untuk {@link LedgerServiceImpl}.
 * <p>
 * Cakupan ujian:
 * <ul>
 *   <li>Test Case 1: Transaksi double-entry berjaya (DEBIT = CREDIT)</li>
 *   <li>Test Case 2: Insufficient balance melempar exception</li>
 *   <li>Pengiriman Kafka event ketika A_POIN terlibat</li>
 *   <li>Integritas balans setelah transaksi</li>
 *   <li>Referensi transaksi dan idempotency key tersimpan dengan betul</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private JournalTransactionRepository journalTransactionRepository;

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private LoyaltyPointsProducer loyaltyPointsProducer;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    private Account customerAccount;
    private Account merchantAccount;
    private CheckoutRequest request;

    @BeforeEach
    void setUp() {
        customerAccount = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("cust-001")
                .accountName("Dompet cust-001 - IDR")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("100000.00"))
                .build();

        merchantAccount = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("merch-001")
                .accountName("Dompet Merchant merch-001 - IDR")
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .balance(new BigDecimal("500000.00"))
                .build();

        request = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("50000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-key-001")
                .tenders(List.of(
                        TenderDetail.builder()
                                .currency(Currency.IDR)
                                .amount(new BigDecimal("50000.00"))
                                .description("Pembelian barang")
                                .build()
                ))
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Test Case 1: Transaksi double-entry berjaya                        */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Test Case 1: Harus buat transaksi double-entry berjaya (DEBIT = CREDIT)")
    void processMultiTenderPayment_shouldCreateSuccessfulDoubleEntryTransaction() {
        // Given
        when(accountRepository.findByOwnerIdAndCurrency("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findByOwnerIdAndCurrency("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));
        when(journalTransactionRepository.save(any(JournalTransaction.class)))
                .thenAnswer(invocation -> {
                    JournalTransaction jt = invocation.getArgument(0);
                    jt.setId(UUID.randomUUID());
                    return jt;
                });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CheckoutResponse response = ledgerService.processMultiTenderPayment(request);

        // Then - Response
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(response.getBalanceRemaining()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(response.getTransactionId()).isNotNull();

        // Then - Double-entry integrity: customer debited, merchant credited
        verify(accountRepository, times(1)).save(customerAccount);
        verify(accountRepository, times(1)).save(merchantAccount);

        assertThat(customerAccount.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(merchantAccount.getBalance()).isEqualByComparingTo(new BigDecimal("550000.00"));

        // Then - Journal transaction saved with correct idempotency key
        verify(journalTransactionRepository, times(1)).save(any(JournalTransaction.class));
        JournalTransaction capturedJournal = captureJournalTransaction();
        assertThat(capturedJournal.getIdempotencyKey()).isEqualTo("idem-key-001");
        assertThat(capturedJournal.getReferenceType()).isEqualTo(ReferenceType.PAYMENT);

        // Then - Ledger entries: exactly 2 entries (1 DEBIT, 1 CREDIT), equal amounts
        ArgumentCaptor<JournalTransaction> journalCaptor = ArgumentCaptor.forClass(JournalTransaction.class);
        verify(journalTransactionRepository).save(journalCaptor.capture());
        JournalTransaction savedJournal = journalCaptor.getValue();
        assertThat(savedJournal.getLedgerEntries()).hasSize(2);

        List<LedgerEntry> entries = savedJournal.getLedgerEntries();
        long debitCount = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .count();
        long creditCount = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .count();

        assertThat(debitCount).isEqualTo(1);
        assertThat(creditCount).isEqualTo(1);

        BigDecimal totalDebit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(totalDebit).isEqualByComparingTo(totalCredit);
        assertThat(totalDebit).isEqualByComparingTo(new BigDecimal("50000.00"));

        // Then - Kafka NOT called (no A_POIN involved)
        verify(loyaltyPointsProducer, never()).sendPointsAwarded(any());
    }

    @Test
    @DisplayName("Harus kirim Kafka event ketika A_POIN terlibat dalam tender")
    void processMultiTenderPayment_shouldSendKafkaEventWhenAPointInvolved() {
        // Given
        CheckoutRequest apoinRequest = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("30000.00"))
                .currency(Currency.A_POIN)
                .idempotencyKey("idem-key-apoin-001")
                .tenders(List.of(
                        TenderDetail.builder()
                                .currency(Currency.A_POIN)
                                .amount(new BigDecimal("30000.00"))
                                .description("Tebasan voucher")
                                .build()
                ))
                .build();

        Account apoinCustomer = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("cust-001")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.A_POIN)
                .balance(new BigDecimal("100000.00"))
                .build();

        Account apoinMerchant = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("merch-001")
                .accountType(AccountType.REVENUE)
                .currency(Currency.A_POIN)
                .balance(new BigDecimal("500000.00"))
                .build();

        when(accountRepository.findByOwnerIdAndCurrency("cust-001", Currency.A_POIN))
                .thenReturn(Optional.of(apoinCustomer));
        when(accountRepository.findByOwnerIdAndCurrency("merch-001", Currency.A_POIN))
                .thenReturn(Optional.of(apoinMerchant));
        when(journalTransactionRepository.save(any(JournalTransaction.class)))
                .thenAnswer(invocation -> {
                    JournalTransaction jt = invocation.getArgument(0);
                    jt.setId(UUID.randomUUID());
                    return jt;
                });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        CheckoutResponse response = ledgerService.processMultiTenderPayment(apoinRequest);

        // Then
        assertThat(response.getStatus()).isEqualTo("SUCCESS");

        // Kafka event should be sent
        ArgumentCaptor<PointsAwardedEvent> eventCaptor = ArgumentCaptor.forClass(PointsAwardedEvent.class);
        verify(loyaltyPointsProducer, times(1)).sendPointsAwarded(eventCaptor.capture());
        PointsAwardedEvent event = eventCaptor.getValue();
        assertThat(event.getOwnerId()).isEqualTo("cust-001");
        assertThat(event.getCurrency()).isEqualTo(Currency.A_POIN);
        assertThat(event.getAmount()).isEqualByComparingTo(new BigDecimal("30000.00"));
        assertThat(event.getTransactionId()).isEqualTo(response.getTransactionId().toString());
    }

    /* ------------------------------------------------------------------ */
    /* Test Case 2: Insufficient balance throws exception                */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Test Case 2: Harus melempar InsufficientBalanceException jika balans tidak mencukupi")
    void processMultiTenderPayment_shouldThrowInsufficientBalanceException() {
        // Given - customer balance 10.000, but request 50.000
        Account poorCustomer = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("cust-001")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.IDR)
                .balance(new BigDecimal("10000.00"))
                .build();

        when(accountRepository.findByOwnerIdAndCurrency("cust-001", Currency.IDR))
                .thenReturn(Optional.of(poorCustomer));
        when(accountRepository.findByOwnerIdAndCurrency("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));

        // When & Then
        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("tidak mencukupi");

        // No journal should be saved
        verify(journalTransactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(loyaltyPointsProducer, never()).sendPointsAwarded(any());
    }

    @Test
    @DisplayName("Harus melempar exception jika akaun pelanggan tidak dijumpai")
    void processMultiTenderPayment_shouldThrowWhenCustomerAccountNotFound() {
        // Given
        when(accountRepository.findByOwnerIdAndCurrency("cust-001", Currency.IDR))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ------------------------------------------------------------------ */
    /* Additional: Balance integrity after multiple transactions         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Harus memelihara integritas balans setelah transaksi berurutan")
    void shouldMaintainBalanceIntegrityAfterSequentialTransactions() {
        // Given
        when(accountRepository.findByOwnerIdAndCurrency("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findByOwnerIdAndCurrency("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));
        when(journalTransactionRepository.save(any(JournalTransaction.class)))
                .thenAnswer(invocation -> {
                    JournalTransaction jt = invocation.getArgument(0);
                    jt.setId(UUID.randomUUID());
                    return jt;
                });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When - first transaction 50.000
        CheckoutRequest req1 = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("50000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-001")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("50000.00"))
                        .description("Transaksi 1")
                        .build()))
                .build();

        ledgerService.processMultiTenderPayment(req1);

        // Second transaction 30.000 (customer now has 50.000)
        CheckoutRequest req2 = CheckoutRequest.builder()
                .ownerId("cust-001")
                .merchantId("merch-001")
                .totalAmount(new BigDecimal("30000.00"))
                .currency(Currency.IDR)
                .idempotencyKey("idem-002")
                .tenders(List.of(TenderDetail.builder()
                        .currency(Currency.IDR)
                        .amount(new BigDecimal("30000.00"))
                        .description("Transaksi 2")
                        .build()))
                .build();

        ledgerService.processMultiTenderPayment(req2);

        // Then - customer: 100.000 - 50.000 - 30.000 = 20.000
        // merchant: 500.000 + 50.000 + 30.000 = 580.000
        assertThat(customerAccount.getBalance()).isEqualByComparingTo(new BigDecimal("20000.00"));
        assertThat(merchantAccount.getBalance()).isEqualByComparingTo(new BigDecimal("580000.00"));

        verify(accountRepository, times(2)).save(customerAccount);
        verify(accountRepository, times(2)).save(merchantAccount);
        verify(journalTransactionRepository, times(2)).save(any(JournalTransaction.class));
    }

    /* ------------------------------------------------------------------ */
    /* Helper                                                             */
    /* ------------------------------------------------------------------ */

    private JournalTransaction captureJournalTransaction() {
        ArgumentCaptor<JournalTransaction> captor = ArgumentCaptor.forClass(JournalTransaction.class);
        verify(journalTransactionRepository).save(captor.capture());
        return captor.getValue();
    }
}