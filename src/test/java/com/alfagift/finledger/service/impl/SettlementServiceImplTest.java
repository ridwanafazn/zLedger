package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.JournalTransaction;
import com.alfagift.finledger.domain.entities.LedgerEntry;
import com.alfagift.finledger.domain.entities.MerchantSettlement;
import com.alfagift.finledger.domain.entities.SettlementItem;
import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import com.alfagift.finledger.domain.enums.SettlementStatus;
import com.alfagift.finledger.dto.SettlementCreateRequest;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.ResourceConflictException;
import com.alfagift.finledger.infrastructure.config.SettlementProperties;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.repository.MerchantSettlementRepository;
import com.alfagift.finledger.repository.SettlementContribution;
import com.alfagift.finledger.repository.SettlementItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link SettlementServiceImpl}.
 * <p>
 * Memverifikasi matematika fee, proteksi periode bertumpuk, payout sekali saja,
 * dan bahwa payout selalu menghasilkan jurnal double-entry yang seimbang.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceImplTest {

    private static final String MERCHANT_ID = "merchant-001";
    private static final String CLEARING_ID = "system-settlement";
    private static final UUID SETTLEMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PAYOUT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private final LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
    private final LocalDateTime to = LocalDateTime.of(2026, 2, 1, 0, 0);

    @Mock
    private MerchantSettlementRepository settlementRepository;
    @Mock
    private SettlementItemRepository settlementItemRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private JournalTransactionRepository journalTransactionRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Query nativeQuery;

    private SettlementServiceImpl settlementService;

    @BeforeEach
    void setUp() {
        SettlementProperties properties =
                new SettlementProperties(new BigDecimal("1.0"), CLEARING_ID);
        settlementService = new SettlementServiceImpl(settlementRepository, settlementItemRepository,
                accountRepository, ledgerEntryRepository, journalTransactionRepository, properties,
                new FinLedgerMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(settlementService, "entityManager", entityManager);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private Account account(UUID id, String ownerId, AccountType type, BigDecimal balance) {
        Account account = Account.builder()
                .id(id)
                .ownerId(ownerId)
                .accountName("Akun " + ownerId)
                .accountType(type)
                .currency(Currency.IDR)
                .balance(balance)
                .openingBalance(BigDecimal.ZERO)
                .status(AccountStatus.ACTIVE)
                .build();
        account.setVersion(0L);
        return account;
    }

    private SettlementContribution contribution(ReferenceType type, EntryType entryType, String amount) {
        return new SettlementContribution(UUID.randomUUID(), type, entryType,
                new BigDecimal(amount), from.plusDays(1));
    }

    private MerchantSettlement settlement(SettlementStatus status, String netAmount) {
        MerchantSettlement settlement = MerchantSettlement.builder()
                .id(SETTLEMENT_ID)
                .merchantId(MERCHANT_ID)
                .currency(Currency.IDR)
                .periodStart(from)
                .periodEnd(to)
                .grossAmount(new BigDecimal("1000.0000"))
                .refundAmount(BigDecimal.ZERO.setScale(4))
                .feePercent(new BigDecimal("1.0000"))
                .feeAmount(new BigDecimal("10.0000"))
                .netAmount(new BigDecimal(netAmount))
                .status(status)
                .idempotencyKey("IDEM-1")
                .build();
        settlement.setVersion(0L);
        return settlement;
    }

    private void stubAdvisoryLock() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.setParameter(anyInt(), any())).thenReturn(nativeQuery);
        lenient().when(nativeQuery.getSingleResult()).thenReturn(null);
    }

    /* ------------------------------------------------------------------ */
    /* Preview                                                            */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("preview")
    class Preview {

        @Test
        @DisplayName("Menghitung gross, refund, fee 1%, dan net dengan skala 4")
        void shouldComputeFeeAndNet() {
            Account merchant = account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                    new BigDecimal("1500.0000"));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(merchant));
            when(ledgerEntryRepository.findSettlementContributions(merchant.getId(), from, to,
                    ReferenceType.PAYMENT, ReferenceType.REFUND))
                    .thenReturn(List.of(
                            contribution(ReferenceType.PAYMENT, EntryType.CREDIT, "1000.00"),
                            contribution(ReferenceType.PAYMENT, EntryType.CREDIT, "500.00"),
                            contribution(ReferenceType.REFUND, EntryType.DEBIT, "200.00")));

            SettlementPreviewResponse response =
                    settlementService.preview(MERCHANT_ID, Currency.IDR, from, to, null);

            assertThat(response.getGrossAmount()).isEqualByComparingTo("1500");
            assertThat(response.getRefundAmount()).isEqualByComparingTo("200");
            assertThat(response.getFeePercent()).isEqualByComparingTo("1.0");
            // (1500 - 200) * 1% = 13
            assertThat(response.getFeeAmount()).isEqualByComparingTo("13");
            assertThat(response.getNetAmount()).isEqualByComparingTo("1287");
            assertThat(response.getPaymentCount()).isEqualTo(2);
            assertThat(response.getRefundCount()).isEqualTo(1);
            assertThat(response.isPayable()).isTrue();
        }

        @Test
        @DisplayName("Merchant tanpa akun menghasilkan angka nol dan tidak payable")
        void shouldReturnZeroWhenMerchantHasNoAccount() {
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.empty());

            SettlementPreviewResponse response =
                    settlementService.preview(MERCHANT_ID, Currency.IDR, from, to, null);

            assertThat(response.getGrossAmount()).isEqualByComparingTo("0");
            assertThat(response.getNetAmount()).isEqualByComparingTo("0");
            assertThat(response.isPayable()).isFalse();
            assertThat(response.getMessage()).contains("tidak positif");
        }

        @Test
        @DisplayName("Refund melebihi penjualan membuat net negatif dan tidak payable")
        void shouldFlagNegativeNetAsNotPayable() {
            Account merchant = account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                    BigDecimal.ZERO);
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(merchant));
            when(ledgerEntryRepository.findSettlementContributions(merchant.getId(), from, to,
                    ReferenceType.PAYMENT, ReferenceType.REFUND))
                    .thenReturn(List.of(
                            contribution(ReferenceType.PAYMENT, EntryType.CREDIT, "100.00"),
                            contribution(ReferenceType.REFUND, EntryType.DEBIT, "250.00")));

            SettlementPreviewResponse response =
                    settlementService.preview(MERCHANT_ID, Currency.IDR, from, to, null);

            assertThat(response.getNetAmount()).isEqualByComparingTo("-150");
            assertThat(response.getFeeAmount()).isEqualByComparingTo("0");
            assertThat(response.isPayable()).isFalse();
        }

        @Test
        @DisplayName("periode terbalik harus ditolak")
        void shouldRejectInvertedPeriod() {
            assertThatThrownBy(() -> settlementService.preview(MERCHANT_ID, Currency.IDR, to, from, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("periodEnd");
        }

        @Test
        @DisplayName("fee di luar 0..100 harus ditolak")
        void shouldRejectInvalidFee() {
            assertThatThrownBy(() -> settlementService.preview(
                    MERCHANT_ID, Currency.IDR, from, to, new BigDecimal("101")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("feePercent");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Create                                                             */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("create")
    class Create {

        private SettlementCreateRequest request() {
            return SettlementCreateRequest.builder()
                    .merchantId(MERCHANT_ID)
                    .currency(Currency.IDR)
                    .periodStart(from)
                    .periodEnd(to)
                    .build();
        }

        @Test
        @DisplayName("Membuat batch PENDING beserta rincian jurnalnya")
        void shouldCreatePendingSettlementWithItems() {
            Account merchant = account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                    BigDecimal.ZERO);
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(merchant));
            when(ledgerEntryRepository.findSettlementContributions(merchant.getId(), from, to,
                    ReferenceType.PAYMENT, ReferenceType.REFUND))
                    .thenReturn(List.of(
                            contribution(ReferenceType.PAYMENT, EntryType.CREDIT, "1000.00"),
                            contribution(ReferenceType.REFUND, EntryType.DEBIT, "100.00")));
            when(settlementRepository.existsOverlappingPeriod(eq(MERCHANT_ID), eq(Currency.IDR),
                    eq(from), eq(to), eq(SettlementStatus.CANCELLED))).thenReturn(false);
            when(settlementRepository.save(any(MerchantSettlement.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            stubAdvisoryLock();

            SettlementResponse response = settlementService.create(request(), "IDEM-NEW");

            assertThat(response.getStatus()).isEqualTo(SettlementStatus.PENDING);
            assertThat(response.getGrossAmount()).isEqualByComparingTo("1000");
            assertThat(response.getRefundAmount()).isEqualByComparingTo("100");
            assertThat(response.getFeeAmount()).isEqualByComparingTo("9");
            assertThat(response.getNetAmount()).isEqualByComparingTo("891");
            assertThat(response.getItems()).hasSize(2);
            assertThat(response.getItems().get(1).getSignedAmount()).isEqualByComparingTo("-100");
        }

        @Test
        @DisplayName("Periode bertumpuk harus ditolak 409")
        void shouldRejectOverlappingPeriod() {
            when(settlementRepository.existsOverlappingPeriod(eq(MERCHANT_ID), eq(Currency.IDR),
                    eq(from), eq(to), eq(SettlementStatus.CANCELLED))).thenReturn(true);
            stubAdvisoryLock();

            assertThatThrownBy(() -> settlementService.create(request(), "IDEM-OVERLAP"))
                    .isInstanceOf(ResourceConflictException.class)
                    .hasMessageContaining("bertumpuk");
        }

        @Test
        @DisplayName("Net tidak positif harus ditolak")
        void shouldRejectNonPositiveNet() {
            Account merchant = account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                    BigDecimal.ZERO);
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(merchant));
            when(ledgerEntryRepository.findSettlementContributions(merchant.getId(), from, to,
                    ReferenceType.PAYMENT, ReferenceType.REFUND))
                    .thenReturn(List.of(contribution(ReferenceType.REFUND, EntryType.DEBIT, "50.00")));
            when(settlementRepository.existsOverlappingPeriod(eq(MERCHANT_ID), eq(Currency.IDR),
                    eq(from), eq(to), eq(SettlementStatus.CANCELLED))).thenReturn(false);
            stubAdvisoryLock();

            assertThatThrownBy(() -> settlementService.create(request(), "IDEM-NEGATIVE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Net settlement harus positif");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Pay                                                                */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("pay")
    class Pay {

        @Test
        @DisplayName("Payout memposting jurnal SETTLEMENT DEBIT merchant / CREDIT clearing")
        void shouldPostBalancedSettlementJournal() {
            MerchantSettlement pending = settlement(SettlementStatus.PENDING, "1000.0000");
            Account merchant = account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                    new BigDecimal("1000.0000"));
            Account clearing = account(UUID.randomUUID(), CLEARING_ID, AccountType.ASSET,
                    BigDecimal.ZERO);

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(pending));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(merchant));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(CLEARING_ID, Currency.IDR))
                    .thenReturn(Optional.of(clearing));
            when(journalTransactionRepository.save(any(JournalTransaction.class))).thenAnswer(invocation -> {
                JournalTransaction journal = invocation.getArgument(0);
                journal.setId(PAYOUT_ID);
                return journal;
            });
            when(settlementRepository.save(any(MerchantSettlement.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SettlementResponse response = settlementService.pay(SETTLEMENT_ID, "PAYOUT-1");

            assertThat(response.getStatus()).isEqualTo(SettlementStatus.PAID);
            assertThat(response.getPayoutTransactionId()).isEqualTo(PAYOUT_ID);
            assertThat(merchant.getBalance()).isEqualByComparingTo("0");
            assertThat(clearing.getBalance()).isEqualByComparingTo("1000");

            ArgumentCaptor<JournalTransaction> captor = ArgumentCaptor.forClass(JournalTransaction.class);
            verify(journalTransactionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
            JournalTransaction journal = captor.getAllValues().get(0);

            assertThat(journal.getReferenceType()).isEqualTo(ReferenceType.SETTLEMENT);
            assertThat(journal.getIdempotencyKey()).isEqualTo("SETTLEMENT-PAYOUT-" + SETTLEMENT_ID);

            BigDecimal debit = journal.getLedgerEntries().stream()
                    .filter(entry -> entry.getEntryType() == EntryType.DEBIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = journal.getLedgerEntries().stream()
                    .filter(entry -> entry.getEntryType() == EntryType.CREDIT)
                    .map(LedgerEntry::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(debit).isEqualByComparingTo(credit);
            assertThat(debit).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("Batch yang sudah PAID tidak bisa dibayar lagi")
        void shouldRejectDoublePayout() {
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.PAID, "1000.0000")));

            assertThatThrownBy(() -> settlementService.pay(SETTLEMENT_ID, "PAYOUT-2"))
                    .isInstanceOf(ResourceConflictException.class)
                    .hasMessageContaining("sudah dibayar");
        }

        @Test
        @DisplayName("Batch CANCELLED tidak bisa dibayar")
        void shouldRejectPayoutOfCancelledBatch() {
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.CANCELLED, "1000.0000")));

            assertThatThrownBy(() -> settlementService.pay(SETTLEMENT_ID, "PAYOUT-3"))
                    .isInstanceOf(ResourceConflictException.class)
                    .hasMessageContaining("dibatalkan");
        }

        @Test
        @DisplayName("Saldo merchant kurang harus ditolak")
        void shouldRejectInsufficientMerchantBalance() {
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.PENDING, "1000.0000")));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.of(account(UUID.randomUUID(), MERCHANT_ID, AccountType.REVENUE,
                            new BigDecimal("500.0000"))));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(CLEARING_ID, Currency.IDR))
                    .thenReturn(Optional.of(account(UUID.randomUUID(), CLEARING_ID, AccountType.ASSET,
                            BigDecimal.ZERO)));

            assertThatThrownBy(() -> settlementService.pay(SETTLEMENT_ID, "PAYOUT-4"))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("Akun merchant tidak ada harus ditolak")
        void shouldRejectMissingMerchantAccount() {
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.PENDING, "1000.0000")));
            when(accountRepository.findAccountByOwnerIdAndCurrencyCustom(MERCHANT_ID, Currency.IDR))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> settlementService.pay(SETTLEMENT_ID, "PAYOUT-5"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tidak ditemukan");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Cancel                                                             */
    /* ------------------------------------------------------------------ */

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("Batch PENDING menjadi CANCELLED dan menyimpan alasan")
        void shouldCancelPendingSettlement() {
            MerchantSettlement pending = settlement(SettlementStatus.PENDING, "1000.0000");
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(pending));
            when(settlementRepository.save(any(MerchantSettlement.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            SettlementResponse response = settlementService.cancel(SETTLEMENT_ID, "Salah periode");

            assertThat(response.getStatus()).isEqualTo(SettlementStatus.CANCELLED);
            assertThat(response.getCancelledAt()).isNotNull();
            assertThat(response.getNotes()).isEqualTo("Salah periode");
        }

        @Test
        @DisplayName("Batch PAID tidak bisa dibatalkan")
        void shouldRejectCancelOfPaidSettlement() {
            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement(SettlementStatus.PAID, "1000.0000")));

            assertThatThrownBy(() -> settlementService.cancel(SETTLEMENT_ID, "terlambat"))
                    .isInstanceOf(ResourceConflictException.class);
        }
    }

    @Test
    @DisplayName("get() pada settlement yang tidak ada harus 404 (EntityNotFound)")
    void shouldThrowWhenSettlementMissing() {
        when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.get(SETTLEMENT_ID))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    @Test
    @DisplayName("Rincian item menyimpan sisi entri dan nominal bertanda")
    void shouldMapItemFields() {
        SettlementItem item = SettlementItem.builder()
                .transactionId(UUID.randomUUID())
                .referenceType(ReferenceType.PAYMENT)
                .entryType(EntryType.CREDIT)
                .amount(new BigDecimal("250.0000"))
                .signedAmount(new BigDecimal("250.0000"))
                .occurredAt(from)
                .build();

        assertThat(item.getSignedAmount()).isEqualByComparingTo("250");
        assertThat(item.getEntryType()).isEqualTo(EntryType.CREDIT);
    }
}
