package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.ReconciliationDiscrepancy;
import com.alfagift.finledger.domain.entities.ReconciliationRun;
import com.alfagift.finledger.domain.enums.AccountStatus;
import com.alfagift.finledger.domain.enums.AccountType;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReferenceType;
import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import com.alfagift.finledger.dto.ReconciliationRunResponse;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.repository.AccountBalanceCheck;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalBalanceCheck;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.ReconciliationDiscrepancyRepository;
import com.alfagift.finledger.repository.ReconciliationRunRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit test untuk {@link ReconciliationServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceImplTest {

    private static final UUID RUN_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private JournalTransactionRepository journalTransactionRepository;
    @Mock
    private ReconciliationRunRepository reconciliationRunRepository;
    @Mock
    private ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
    @Mock
    private FinLedgerMetrics metrics;

    @InjectMocks
    private ReconciliationServiceImpl reconciliationService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(reconciliationRunRepository.save(any(ReconciliationRun.class)))
                .thenAnswer(invocation -> {
                    ReconciliationRun run = invocation.getArgument(0);
                    run.setId(RUN_ID);
                    for (ReconciliationDiscrepancy discrepancy : run.getDiscrepancies()) {
                        discrepancy.setId(UUID.randomUUID());
                    }
                    return run;
                });
    }

    private Account account(String ownerId, BigDecimal opening, BigDecimal balance) {
        return Account.builder()
                .id(UUID.randomUUID())
                .ownerId(ownerId)
                .accountName("Akun " + ownerId)
                .accountType(AccountType.REVENUE)
                .currency(Currency.IDR)
                .openingBalance(opening)
                .balance(balance)
                .status(AccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Semua akun cocok dan semua jurnal seimbang -> BALANCED tanpa temuan")
    void shouldReportBalancedWhenEverythingMatches() {
        when(accountRepository.count()).thenReturn(3L);
        when(accountRepository.findBalanceMismatches(EntryType.CREDIT)).thenReturn(List.of());
        when(journalTransactionRepository.findUnbalancedJournals(EntryType.DEBIT)).thenReturn(List.of());
        when(journalTransactionRepository.findJournalIdsWithInsufficientEntries()).thenReturn(List.of());

        ReconciliationRunResponse response = reconciliationService.run("admin", "rutin harian");

        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.BALANCED);
        assertThat(response.getAccountsChecked()).isEqualTo(3);
        assertThat(response.getDiscrepancyCount()).isZero();
        assertThat(response.getTotalAbsVariance()).isEqualByComparingTo("0");
        assertThat(response.getTriggeredBy()).isEqualTo("admin");
        assertThat(response.getDiscrepancies()).isEmpty();
    }

    @Test
    @DisplayName("Selisih saldo akun tercatat sebagai BALANCE_MISMATCH dengan variance benar")
    void shouldRecordBalanceMismatch() {
        Account drifted = account("merchant-x", new BigDecimal("0"), new BigDecimal("9999.0000"));

        when(accountRepository.count()).thenReturn(1L);
        when(accountRepository.findBalanceMismatches(EntryType.CREDIT))
                .thenReturn(List.of(new AccountBalanceCheck(drifted, new BigDecimal("120.0000"))));
        when(journalTransactionRepository.findUnbalancedJournals(EntryType.DEBIT)).thenReturn(List.of());
        when(journalTransactionRepository.findJournalIdsWithInsufficientEntries()).thenReturn(List.of());

        ReconciliationRunResponse response = reconciliationService.run("admin", null);

        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.DISCREPANCY);
        assertThat(response.getDiscrepancyCount()).isEqualTo(1);
        assertThat(response.getTotalAbsVariance()).isEqualByComparingTo("9879");

        var discrepancy = response.getDiscrepancies().get(0);
        assertThat(discrepancy.getDiscrepancyType())
                .isEqualTo(ReconciliationDiscrepancy.TYPE_BALANCE_MISMATCH);
        assertThat(discrepancy.getExpectedBalance()).isEqualByComparingTo("120");
        assertThat(discrepancy.getActualBalance()).isEqualByComparingTo("9999");
        assertThat(discrepancy.getVariance()).isEqualByComparingTo("9879");
        assertThat(discrepancy.getAccountId()).isEqualTo(drifted.getId());
    }

    @Test
    @DisplayName("Jurnal tidak seimbang dan jurnal kurang entri ikut dilaporkan")
    void shouldRecordUnbalancedAndIncompleteJournals() {
        UUID unbalancedId = UUID.randomUUID();
        UUID incompleteId = UUID.randomUUID();

        when(accountRepository.count()).thenReturn(0L);
        when(accountRepository.findBalanceMismatches(eq(EntryType.CREDIT))).thenReturn(List.of());
        when(journalTransactionRepository.findUnbalancedJournals(EntryType.DEBIT))
                .thenReturn(List.of(new JournalBalanceCheck(unbalancedId, ReferenceType.PAYMENT,
                        "checkout rusak", new BigDecimal("250.0000"))));
        when(journalTransactionRepository.findJournalIdsWithInsufficientEntries())
                .thenReturn(List.of(incompleteId));

        ReconciliationRunResponse response = reconciliationService.run(null, "audit manual");

        assertThat(response.getStatus()).isEqualTo(ReconciliationStatus.DISCREPANCY);
        assertThat(response.getDiscrepancyCount()).isEqualTo(2);
        assertThat(response.getUnbalancedJournalCount()).isEqualTo(2);
        assertThat(response.getTotalAbsVariance()).isEqualByComparingTo("250");
        assertThat(response.getDiscrepancies())
                .anyMatch(d -> d.getDetail().contains(unbalancedId.toString()))
                .anyMatch(d -> d.getDetail().contains(incompleteId.toString()));
    }

    @Test
    @DisplayName("Detail run yang tidak ada harus 404")
    void shouldThrowWhenRunMissing() {
        when(reconciliationRunRepository.findById(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reconciliationService.get(RUN_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("Detail run memuat seluruh temuan terurut")
    void shouldReturnRunDetailWithDiscrepancies() {
        ReconciliationRun run = ReconciliationRun.builder()
                .id(RUN_ID)
                .status(ReconciliationStatus.DISCREPANCY)
                .accountsChecked(2)
                .discrepancyCount(1)
                .unbalancedJournalCount(0)
                .totalAbsVariance(new BigDecimal("10.0000"))
                .startedAt(java.time.LocalDateTime.now())
                .build();

        when(reconciliationRunRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(reconciliationDiscrepancyRepository.findByRunIdOrderByDiscrepancyTypeAsc(RUN_ID))
                .thenReturn(List.of(ReconciliationDiscrepancy.builder()
                        .id(UUID.randomUUID())
                        .discrepancyType(ReconciliationDiscrepancy.TYPE_BALANCE_MISMATCH)
                        .variance(new BigDecimal("10.0000"))
                        .build()));

        ReconciliationRunResponse response = reconciliationService.get(RUN_ID);

        assertThat(response.getDiscrepancies()).hasSize(1);
        assertThat(response.getDiscrepancies().get(0).getVariance()).isEqualByComparingTo("10");
    }
}
