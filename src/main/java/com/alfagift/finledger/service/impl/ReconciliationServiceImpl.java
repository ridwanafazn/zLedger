package com.alfagift.finledger.service.impl;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.entities.ReconciliationDiscrepancy;
import com.alfagift.finledger.domain.entities.ReconciliationRun;
import com.alfagift.finledger.domain.enums.EntryType;
import com.alfagift.finledger.domain.enums.ReconciliationStatus;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.ReconciliationDiscrepancyResponse;
import com.alfagift.finledger.dto.ReconciliationRunResponse;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.repository.AccountBalanceCheck;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalBalanceCheck;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.ReconciliationDiscrepancyRepository;
import com.alfagift.finledger.repository.ReconciliationRunRepository;
import com.alfagift.finledger.service.ReconciliationService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Implementasi {@link ReconciliationService}.
 * <p>
 * Tiga pemeriksaan dalam satu run:
 * <ol>
 *   <li><b>Selisih saldo</b> — cached balance akun vs {@code opening + CREDIT - DEBIT}.</li>
 *   <li><b>Jurnal tidak seimbang</b> — total DEBIT ≠ total CREDIT.</li>
 *   <li><b>Jurnal kurang dari 2 entri</b> — melanggar aturan double-entry.</li>
 * </ol>
 * Semua temuan disimpan sehingga dapat diaudit ulang kapan saja.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationServiceImpl implements ReconciliationService {

    private static final int MONEY_SCALE = 4;

    private final AccountRepository accountRepository;
    private final JournalTransactionRepository journalTransactionRepository;
    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationDiscrepancyRepository reconciliationDiscrepancyRepository;
    private final FinLedgerMetrics metrics;

    @Override
    @Transactional
    public ReconciliationRunResponse run(String triggeredBy, String notes) {
        LocalDateTime startedAt = LocalDateTime.now();
        long startNanos = System.nanoTime();
        long accountsChecked = accountRepository.count();

        List<AccountBalanceCheck> mismatches = accountRepository.findBalanceMismatches(EntryType.CREDIT);
        List<JournalBalanceCheck> unbalanced = journalTransactionRepository.findUnbalancedJournals(EntryType.DEBIT);
        List<UUID> insufficient = journalTransactionRepository.findJournalIdsWithInsufficientEntries();

        ReconciliationRun run = ReconciliationRun.builder()
                .status(ReconciliationStatus.BALANCED)
                .accountsChecked((int) accountsChecked)
                .triggeredBy(triggeredBy)
                .notes(notes)
                .startedAt(startedAt)
                .build();

        BigDecimal totalAbsVariance = BigDecimal.ZERO;

        for (AccountBalanceCheck check : mismatches) {
            Account account = check.account();
            BigDecimal movement = normalize(check.ledgerMovement());
            BigDecimal expected = normalize(account.getOpeningBalance().add(movement));
            BigDecimal actual = normalize(account.getBalance());
            BigDecimal variance = normalize(actual.subtract(expected));
            totalAbsVariance = totalAbsVariance.add(variance.abs());

            run.addDiscrepancy(ReconciliationDiscrepancy.builder()
                    .accountId(account.getId())
                    .discrepancyType(ReconciliationDiscrepancy.TYPE_BALANCE_MISMATCH)
                    .expectedBalance(expected)
                    .actualBalance(actual)
                    .variance(variance)
                    .detail(String.format(
                            "ownerId=%s, currency=%s, opening=%s, movement=%s",
                            account.getOwnerId(), account.getCurrency(),
                            account.getOpeningBalance(), movement))
                    .build());
        }

        for (JournalBalanceCheck check : unbalanced) {
            BigDecimal imbalance = normalize(check.imbalance());
            totalAbsVariance = totalAbsVariance.add(imbalance.abs());

            run.addDiscrepancy(ReconciliationDiscrepancy.builder()
                    .discrepancyType(ReconciliationDiscrepancy.TYPE_UNBALANCED_JOURNAL)
                    .variance(imbalance)
                    .detail(String.format(
                            "Jurnal %s (%s) tidak seimbang: debit - credit = %s. %s",
                            check.id(), check.referenceType(), imbalance,
                            check.description() == null ? "" : check.description()))
                    .build());
        }

        for (UUID journalId : insufficient) {
            run.addDiscrepancy(ReconciliationDiscrepancy.builder()
                    .discrepancyType(ReconciliationDiscrepancy.TYPE_UNBALANCED_JOURNAL)
                    .detail("Jurnal " + journalId + " memiliki kurang dari 2 entri ledger")
                    .build());
        }

        run.setTotalAbsVariance(normalize(totalAbsVariance));
        run.setUnbalancedJournalCount(unbalanced.size() + insufficient.size());
        run.setDiscrepancyCount(run.getDiscrepancies().size());
        run.setStatus(run.getDiscrepancyCount() == 0
                ? ReconciliationStatus.BALANCED : ReconciliationStatus.DISCREPANCY);
        run.setCompletedAt(LocalDateTime.now());

        ReconciliationRun saved = reconciliationRunRepository.save(run);

        metrics.reconciliationCompleted(saved.getStatus(), saved.getDiscrepancyCount(),
                (System.nanoTime() - startNanos) / 1_000_000);

        log.info("Rekonsiliasi selesai: id={}, status={}, akun={}, temuan={}, jurnalTidakSeimbang={}",
                saved.getId(), saved.getStatus(), saved.getAccountsChecked(),
                saved.getDiscrepancyCount(), saved.getUnbalancedJournalCount());

        return toResponse(saved, saved.getDiscrepancies());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReconciliationRunResponse> list(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "completedAt"));

        Page<ReconciliationRun> runs = reconciliationRunRepository.findAll(pageable);

        return PageResponse.<ReconciliationRunResponse>builder()
                .content(runs.getContent().stream().map(r -> toResponse(r, null)).toList())
                .page(PageResponse.PageMetadata.builder()
                        .number(runs.getNumber())
                        .size(runs.getSize())
                        .totalElements(runs.getTotalElements())
                        .totalPages(runs.getTotalPages())
                        .first(runs.isFirst())
                        .last(runs.isLast())
                        .numberOfElements(runs.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("completedAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationRunResponse get(UUID runId) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("Rekonsiliasi tidak ditemukan: " + runId));

        List<ReconciliationDiscrepancy> discrepancies =
                reconciliationDiscrepancyRepository.findByRunIdOrderByDiscrepancyTypeAsc(runId);

        return toResponse(run, discrepancies);
    }

    private ReconciliationRunResponse toResponse(ReconciliationRun run,
                                                 List<ReconciliationDiscrepancy> discrepancies) {
        return ReconciliationRunResponse.builder()
                .id(run.getId())
                .status(run.getStatus())
                .accountsChecked(run.getAccountsChecked())
                .discrepancyCount(run.getDiscrepancyCount())
                .unbalancedJournalCount(run.getUnbalancedJournalCount())
                .totalAbsVariance(run.getTotalAbsVariance())
                .triggeredBy(run.getTriggeredBy())
                .notes(run.getNotes())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .discrepancies(discrepancies == null
                        ? null
                        : discrepancies.stream().map(this::toDiscrepancyResponse).toList())
                .build();
    }

    private ReconciliationDiscrepancyResponse toDiscrepancyResponse(ReconciliationDiscrepancy discrepancy) {
        return ReconciliationDiscrepancyResponse.builder()
                .id(discrepancy.getId())
                .accountId(discrepancy.getAccountId())
                .discrepancyType(discrepancy.getDiscrepancyType())
                .expectedBalance(discrepancy.getExpectedBalance())
                .actualBalance(discrepancy.getActualBalance())
                .variance(discrepancy.getVariance())
                .detail(discrepancy.getDetail())
                .createdAt(discrepancy.getCreatedAt())
                .build();
    }

    private BigDecimal normalize(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
