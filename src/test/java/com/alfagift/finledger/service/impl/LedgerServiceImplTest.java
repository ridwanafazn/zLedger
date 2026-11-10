package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.AccountStatus;
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
import com.alfagift.finledger.infrastructure.AccountInactiveException;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.outbox.OutboxService;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import jakarta.persistence.EntityNotFoundException;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private OutboxService outboxService;

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
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
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
        // (disimpan 2 kali: sekali untuk generate ID, sekali setelah menambahkan ledger entries)
        verify(journalTransactionRepository, times(2)).save(any(JournalTransaction.class));
        JournalTransaction capturedJournal = captureJournalTransaction();
        assertThat(capturedJournal.getIdempotencyKey()).isEqualTo("idem-key-001");
        assertThat(capturedJournal.getReferenceType()).isEqualTo(ReferenceType.PAYMENT);

        // Then - Ledger entries: exactly 2 entries (1 DEBIT, 1 CREDIT), equal amounts
        assertThat(capturedJournal.getLedgerEntries()).hasSize(2);

        List<LedgerEntry> entries = capturedJournal.getLedgerEntries();
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
        verify(outboxService, never()).enqueue(anyString(), anyString(), anyString(), any());
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

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.A_POIN))
                .thenReturn(Optional.of(apoinCustomer));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.A_POIN))
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

        // Event harus didaftarkan ke outbox (bukan langsung ke Kafka)
        ArgumentCaptor<PointsAwardedEvent> eventCaptor = ArgumentCaptor.forClass(PointsAwardedEvent.class);
        verify(outboxService, times(1)).enqueue(
                eq("JOURNAL_TRANSACTION"), anyString(), eq("POINTS_AWARDED"), eventCaptor.capture());
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

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(poorCustomer));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));

        // When & Then
        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("tidak mencukupi");

        // No journal should be saved
        verify(journalTransactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(outboxService, never()).enqueue(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Harus melempar InsufficientBalanceException jika akaun pelanggan tidak dijumpai (dibuat dengan balans nol)")
    void processMultiTenderPayment_shouldThrowWhenCustomerAccountNotFound() {
        // Given - akaun tidak dijumpai, sistem akan mencipta akaun baru dengan balans nol
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.empty());
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When & Then - balans nol tidak mencukupi untuk totalAmount 50000
        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("tidak mencukupi");

        // Tidak ada journal yang disimpan
        verify(journalTransactionRepository, never()).save(any());
    }

    /* ------------------------------------------------------------------ */
    /* Additional: Balance integrity after multiple transactions         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Harus memelihara integritas balans setelah transaksi berurutan")
    void shouldMaintainBalanceIntegrityAfterSequentialTransactions() {
        // Given
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
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
        verify(journalTransactionRepository, times(4)).save(any(JournalTransaction.class));
    }

    /* ------------------------------------------------------------------ */
    /* Sprint B: locking & status guard                                    */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Harus mengunci akaun dalam urutan deterministik (anti-deadlock)")
    void shouldLockAccountsInDeterministicOrder() {
        // Given — dua akaun yang sudah pernah di-flush (version terisi)
        UUID customerId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID merchantId = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        // customer sengaja diberi UUID "besar", merchant UUID "kecil"
        customerAccount.setId(customerId);
        customerAccount.setVersion(0L);
        merchantAccount.setId(merchantId);
        merchantAccount.setVersion(0L);

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));
        when(journalTransactionRepository.save(any(JournalTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Account firstExpected = customerId.compareTo(merchantId) <= 0 ? customerAccount : merchantAccount;
        Account secondExpected = firstExpected.equals(customerAccount) ? merchantAccount : customerAccount;

        // When
        ledgerService.processMultiTenderPayment(request);

        // Then — urutan lock mengikuti compareTo UUID, bukan urutan bisnis
        org.mockito.InOrder inOrder = inOrder(accountRepository);
        inOrder.verify(accountRepository).lockForUpdate(firstExpected);
        inOrder.verify(accountRepository).lockForUpdate(secondExpected);
    }

    @Test
    @DisplayName("Harus menolak transaksi bila akun pelanggan SUSPENDED")
    void shouldRejectSuspendedCustomer() {
        customerAccount.setStatus(AccountStatus.SUSPENDED);

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));

        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(AccountInactiveException.class)
                .hasMessageContaining("SUSPENDED");

        verify(journalTransactionRepository, never()).save(any(JournalTransaction.class));
    }

    @Test
    @DisplayName("Harus menolak transaksi bila akun merchant CLOSED")
    void shouldRejectClosedMerchant() {
        merchantAccount.setStatus(AccountStatus.CLOSED);

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.IDR))
                .thenReturn(Optional.of(customerAccount));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("merch-001", Currency.IDR))
                .thenReturn(Optional.of(merchantAccount));

        assertThatThrownBy(() -> ledgerService.processMultiTenderPayment(request))
                .isInstanceOf(AccountInactiveException.class)
                .hasMessageContaining("CLOSED");

        verify(journalTransactionRepository, never()).save(any(JournalTransaction.class));
    }

    @Test
    @DisplayName("Redeem harus menolak akun pelanggan yang SUSPENDED")
    void redeemShouldRejectSuspendedCustomer() {
        Account apoinCustomer = Account.builder()
                .id(UUID.randomUUID())
                .ownerId("cust-001")
                .accountName("Dompet cust-001 - A_POIN")
                .accountType(AccountType.LIABILITY)
                .currency(Currency.A_POIN)
                .balance(new BigDecimal("100000.00"))
                .status(AccountStatus.SUSPENDED)
                .build();

        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("cust-001", Currency.A_POIN))
                .thenReturn(Optional.of(apoinCustomer));
        when(accountRepository.findAccountByOwnerIdAndCurrencyCustom("system", Currency.A_POIN))
                .thenReturn(Optional.of(Account.builder()
                        .id(UUID.randomUUID())
                        .ownerId("system")
                        .accountName("Pool Promosi Sistem - A_POIN")
                        .accountType(AccountType.EXPENSE)
                        .currency(Currency.A_POIN)
                        .balance(new BigDecimal("500000.00"))
                        .build()));

        assertThatThrownBy(() -> ledgerService.redeemFlashSale(
                "cust-001", UUID.randomUUID(), 1000, "idem-redeem-001"))
                .isInstanceOf(AccountInactiveException.class);

        verify(journalTransactionRepository, never()).save(any(JournalTransaction.class));
    }

    /* ------------------------------------------------------------------ */
    /* Sprint C: query transaksi                                           */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("listTransactions harus memetakan ringkasan dan total debit")
    void listTransactions_shouldMapSummary() {
        JournalTransaction journal = JournalTransaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("idem-list-001")
                .referenceType(ReferenceType.PAYMENT)
                .description("Pembayaran")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        journal.addLedgerEntry(LedgerEntry.builder()
                .journalTransaction(journal).account(customerAccount)
                .entryType(EntryType.DEBIT).amount(new BigDecimal("50000.00")).build());
        journal.addLedgerEntry(LedgerEntry.builder()
                .journalTransaction(journal).account(merchantAccount)
                .entryType(EntryType.CREDIT).amount(new BigDecimal("50000.00")).build());

        when(journalTransactionRepository.findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(journal), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var page = ledgerService.listTransactions(0, 20, null, null, null, null);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getEntryCount()).isEqualTo(2);
        assertThat(page.getContent().get(0).getTotalAmount()).isEqualByComparingTo("50000.00");
        assertThat(page.getContent().get(0).getReferenceType()).isEqualTo(ReferenceType.PAYMENT);
        assertThat(page.getPage().getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("listTransactions harus menolak referenceType tidak valid")
    void listTransactions_shouldRejectInvalidReferenceType() {
        assertThatThrownBy(() -> ledgerService.listTransactions(0, 20, "FOO", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referenceType");

        verify(journalTransactionRepository, never()).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    @DisplayName("getTransaction harus mengembalikan detail dan menandai balanced")
    void getTransaction_shouldReturnBalancedDetail() {
        UUID transactionId = UUID.randomUUID();
        JournalTransaction journal = JournalTransaction.builder()
                .id(transactionId)
                .idempotencyKey("idem-detail-001")
                .referenceType(ReferenceType.PAYMENT)
                .build();

        LedgerEntry debit = LedgerEntry.builder()
                .id(UUID.randomUUID()).journalTransaction(journal).account(customerAccount)
                .entryType(EntryType.DEBIT).amount(new BigDecimal("50000.00")).build();
        LedgerEntry credit = LedgerEntry.builder()
                .id(UUID.randomUUID()).journalTransaction(journal).account(merchantAccount)
                .entryType(EntryType.CREDIT).amount(new BigDecimal("50000.00")).build();

        when(journalTransactionRepository.findById(transactionId)).thenReturn(Optional.of(journal));
        when(ledgerEntryRepository.findByJournalTransactionId(transactionId))
                .thenReturn(List.of(debit, credit));

        var detail = ledgerService.getTransaction(transactionId);

        assertThat(detail.isBalanced()).isTrue();
        assertThat(detail.getTotalDebit()).isEqualByComparingTo("50000.00");
        assertThat(detail.getTotalCredit()).isEqualByComparingTo("50000.00");
        assertThat(detail.getEntries()).hasSize(2);
        assertThat(detail.getEntries().get(0).getOwnerId()).isEqualTo("cust-001");
    }

    @Test
    @DisplayName("getTransaction transaksi tidak ada harus melempar EntityNotFoundException")
    void getTransaction_notFound_shouldThrow() {
        UUID transactionId = UUID.randomUUID();
        when(journalTransactionRepository.findById(transactionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.getTransaction(transactionId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    /* ------------------------------------------------------------------ */
    /* Sprint E: reversal                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Reversal harus membuat jurnal REFUND dengan entri kompensasi")
    void reverseTransaction_shouldCreateCompensatingEntries() {
        UUID transactionId = UUID.randomUUID();
        JournalTransaction original = JournalTransaction.builder()
                .id(transactionId)
                .idempotencyKey("idem-original")
                .referenceType(ReferenceType.PAYMENT)
                .build();

        LedgerEntry debit = LedgerEntry.builder()
                .id(UUID.randomUUID()).journalTransaction(original).account(customerAccount)
                .entryType(EntryType.DEBIT).amount(new BigDecimal("50000.00")).build();
        LedgerEntry credit = LedgerEntry.builder()
                .id(UUID.randomUUID()).journalTransaction(original).account(merchantAccount)
                .entryType(EntryType.CREDIT).amount(new BigDecimal("50000.00")).build();

        when(journalTransactionRepository.findById(transactionId)).thenReturn(Optional.of(original));
        when(journalTransactionRepository.existsByReversalOfId(transactionId)).thenReturn(false);
        when(ledgerEntryRepository.findByJournalTransactionId(transactionId))
                .thenReturn(List.of(debit, credit));
        when(journalTransactionRepository.save(any(JournalTransaction.class)))
                .thenAnswer(invocation -> {
                    JournalTransaction jt = invocation.getArgument(0);
                    if (jt.getId() == null) {
                        jt.setId(UUID.randomUUID());
                    }
                    return jt;
                });
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = ledgerService.reverseTransaction(transactionId, "barang dikembalikan");

        assertThat(response.getStatus()).isEqualTo("REVERSED");
        assertThat(response.getReferenceType()).isEqualTo(ReferenceType.REFUND);
        assertThat(response.getOriginalTransactionId()).isEqualTo(transactionId);
        assertThat(response.getReversalTransactionId()).isNotNull();
        assertThat(response.getTotalDebit()).isEqualByComparingTo("50000.00");
        assertThat(response.getTotalCredit()).isEqualByComparingTo("50000.00");

        // Saldo dibalik: pelanggan +50.000, merchant -50.000
        assertThat(customerAccount.getBalance()).isEqualByComparingTo("150000.00");
        assertThat(merchantAccount.getBalance()).isEqualByComparingTo("450000.00");
        verify(accountRepository).save(customerAccount);
        verify(accountRepository).save(merchantAccount);
    }

    @Test
    @DisplayName("Transaksi yang sudah dibalik tidak boleh dibalik lagi")
    void reverseTransaction_twice_shouldBeRejected() {
        UUID transactionId = UUID.randomUUID();
        JournalTransaction original = JournalTransaction.builder()
                .id(transactionId)
                .referenceType(ReferenceType.PAYMENT)
                .build();

        when(journalTransactionRepository.findById(transactionId)).thenReturn(Optional.of(original));
        when(journalTransactionRepository.existsByReversalOfId(transactionId)).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.reverseTransaction(transactionId, "dobel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah pernah dibalik");

        verify(journalTransactionRepository, never()).save(any(JournalTransaction.class));
    }

    @Test
    @DisplayName("Transaksi REFUND tidak boleh dibalik")
    void reverseTransaction_refund_shouldBeRejected() {
        UUID transactionId = UUID.randomUUID();
        JournalTransaction refund = JournalTransaction.builder()
                .id(transactionId)
                .referenceType(ReferenceType.REFUND)
                .build();

        when(journalTransactionRepository.findById(transactionId)).thenReturn(Optional.of(refund));

        assertThatThrownBy(() -> ledgerService.reverseTransaction(transactionId, "salah"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refund");
    }

    /* ------------------------------------------------------------------ */
    /* Helper                                                             */
    /* ------------------------------------------------------------------ */

    private JournalTransaction captureJournalTransaction() {
        ArgumentCaptor<JournalTransaction> captor = ArgumentCaptor.forClass(JournalTransaction.class);
        // save dipanggil 2 kali per transaksi (generate ID + setelah menambahkan ledger entries).
        // Kedua capture merujuk pada objek journal yang sama, sehingga entri ledger sudah lengkap.
        verify(journalTransactionRepository, times(2)).save(captor.capture());
        return captor.getValue();
    }
}