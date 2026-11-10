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
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.SettlementCreateRequest;
import com.alfagift.finledger.dto.SettlementItemResponse;
import com.alfagift.finledger.dto.SettlementPreviewResponse;
import com.alfagift.finledger.dto.SettlementResponse;
import com.alfagift.finledger.infrastructure.AccountInactiveException;
import com.alfagift.finledger.infrastructure.Idempotent;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.ResourceConflictException;
import com.alfagift.finledger.infrastructure.config.SettlementProperties;
import com.alfagift.finledger.infrastructure.metrics.FinLedgerMetrics;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.repository.MerchantSettlementRepository;
import com.alfagift.finledger.repository.SettlementContribution;
import com.alfagift.finledger.repository.SettlementItemRepository;
import com.alfagift.finledger.service.SettlementService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementasi {@link SettlementService}.
 * <p>
 * Jaminan integritas:
 * <ol>
 *   <li><b>Periode tidak bertumpuk</b> â€” dicek di bawah PostgreSQL advisory lock
 *       per merchant+currency sehingga dua admin yang membuat batch paralel
 *       tidak bisa menghasilkan periode overlap.</li>
 *   <li><b>Payout sekali saja</b> â€” baris settlement dikunci
 *       {@code PESSIMISTIC_WRITE} lalu status diperiksa ulang.</li>
 *   <li><b>Double-entry</b> â€” payout selalu menghasilkan pasangan DEBIT merchant
 *       dan CREDIT akun clearing dengan nominal sama.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementServiceImpl implements SettlementService {

    private static final int MONEY_SCALE = 4;
    private static final int PERCENT_SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final MerchantSettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final JournalTransactionRepository journalTransactionRepository;
    private final SettlementProperties settlementProperties;
    private final FinLedgerMetrics metrics;

    @PersistenceContext
    private EntityManager entityManager;

    /* ------------------------------------------------------------------ */
    /* Preview                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public SettlementPreviewResponse preview(String merchantId, Currency currency,
                                             LocalDateTime periodStart, LocalDateTime periodEnd,
                                             BigDecimal feePercent) {
        validatePeriod(periodStart, periodEnd);
        BigDecimal effectiveFee = resolveFeePercent(feePercent);
        Computation computation = compute(merchantId, currency, periodStart, periodEnd, effectiveFee);

        boolean payable = computation.net().signum() > 0;
        return SettlementPreviewResponse.builder()
                .merchantId(merchantId)
                .currency(currency)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .grossAmount(computation.gross())
                .refundAmount(computation.refund())
                .feePercent(effectiveFee)
                .feeAmount(computation.fee())
                .netAmount(computation.net())
                .paymentCount(computation.paymentCount())
                .refundCount(computation.refundCount())
                .payable(payable)
                .message(payable
                        ? "Batch dapat dibuat"
                        : "Net settlement tidak positif; batch tidak dapat dibuat")
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Create                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional
    @Idempotent
    public SettlementResponse create(SettlementCreateRequest request, String idempotencyKey) {
        validatePeriod(request.getPeriodStart(), request.getPeriodEnd());
        BigDecimal effectiveFee = resolveFeePercent(request.getFeePercent());

        // Serialisasi per merchant + currency: cek overlap dan insert harus atomik.
        acquireMerchantLock(request.getMerchantId(), request.getCurrency());

        if (settlementRepository.existsOverlappingPeriod(request.getMerchantId(), request.getCurrency(),
                request.getPeriodStart(), request.getPeriodEnd(), SettlementStatus.CANCELLED)) {
            metrics.settlementRejected("overlap");
            throw new ResourceConflictException(String.format(
                    "Periode %s s/d %s bertumpuk dengan batch settlement lain untuk merchant %s (%s)",
                    request.getPeriodStart(), request.getPeriodEnd(), request.getMerchantId(), request.getCurrency()));
        }

        Computation computation = compute(request.getMerchantId(), request.getCurrency(),
                request.getPeriodStart(), request.getPeriodEnd(), effectiveFee);

        if (computation.net().signum() <= 0) {
            metrics.settlementRejected("non_positive_net");
            throw new IllegalArgumentException(String.format(
                    "Net settlement harus positif (gross=%s, refund=%s, fee=%s, net=%s)",
                    computation.gross(), computation.refund(), computation.fee(), computation.net()));
        }

        MerchantSettlement settlement = MerchantSettlement.builder()
                .merchantId(request.getMerchantId())
                .currency(request.getCurrency())
                .periodStart(request.getPeriodStart())
                .periodEnd(request.getPeriodEnd())
                .grossAmount(computation.gross())
                .refundAmount(computation.refund())
                .feePercent(effectiveFee)
                .feeAmount(computation.fee())
                .netAmount(computation.net())
                .status(SettlementStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .notes(request.getNotes())
                .build();

        for (SettlementContribution contribution : computation.contributions()) {
            settlement.addItem(toItem(contribution));
        }

        MerchantSettlement saved = settlementRepository.save(settlement);
        metrics.settlementCreated(saved.getCurrency().name(), saved.getNetAmount());
        log.info("Settlement dibuat: id={}, merchant={}, periode={}..{}, net={}",
                saved.getId(), saved.getMerchantId(), saved.getPeriodStart(), saved.getPeriodEnd(), saved.getNetAmount());

        return toResponse(saved, true);
    }

    /* ------------------------------------------------------------------ */
    /* Read                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(readOnly = true)
    public SettlementResponse get(UUID settlementId) {
        return toResponse(findSettlement(settlementId), true);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SettlementResponse> search(String merchantId, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        SettlementStatus statusFilter = parseStatus(status);
        Specification<MerchantSettlement> specification = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (merchantId != null && !merchantId.isBlank()) {
                predicates.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (statusFilter != null) {
                predicates.add(cb.equal(root.get("status"), statusFilter));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<MerchantSettlement> settlements = settlementRepository.findAll(specification, pageable);

        return PageResponse.<SettlementResponse>builder()
                .content(settlements.getContent().stream().map(s -> toResponse(s, false)).toList())
                .page(PageResponse.PageMetadata.builder()
                        .number(settlements.getNumber())
                        .size(settlements.getSize())
                        .totalElements(settlements.getTotalElements())
                        .totalPages(settlements.getTotalPages())
                        .first(settlements.isFirst())
                        .last(settlements.isLast())
                        .numberOfElements(settlements.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* Pay / cancel                                                       */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional
    @Idempotent
    public SettlementResponse pay(UUID settlementId, String idempotencyKey) {
        MerchantSettlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new EntityNotFoundException("Settlement tidak ditemukan: " + settlementId));

        if (settlement.getStatus() == SettlementStatus.PAID) {
            metrics.settlementRejected("already_paid");
            throw new ResourceConflictException(
                    "Settlement " + settlementId + " sudah dibayar (payout " + settlement.getPayoutTransactionId() + ")");
        }
        if (settlement.getStatus() == SettlementStatus.CANCELLED) {
            metrics.settlementRejected("cancelled");
            throw new ResourceConflictException("Settlement " + settlementId + " sudah dibatalkan");
        }
        if (settlement.getNetAmount().signum() <= 0) {
            metrics.settlementRejected("non_positive_net");
            throw new ResourceConflictException(
                    "Settlement " + settlementId + " tidak punya net positif untuk dibayar");
        }

        Account merchantAccount = accountRepository
                .findAccountByOwnerIdAndCurrencyCustom(settlement.getMerchantId(), settlement.getCurrency())
                .orElseThrow(() -> new IllegalArgumentException(String.format(
                        "Akun merchant %s (%s) tidak ditemukan", settlement.getMerchantId(), settlement.getCurrency())));

        Account clearingAccount = getOrCreateClearingAccount(settlement.getCurrency());
        lockAccountsForUpdate(merchantAccount, clearingAccount);

        ensureActive(merchantAccount);
        ensureActive(clearingAccount);

        BigDecimal net = settlement.getNetAmount();
        if (merchantAccount.getBalance().compareTo(net) < 0) {
            throw new InsufficientBalanceException(String.format(
                    "Saldo merchant %s tidak cukup untuk payout settlement %s. Tersedia: %s, dibutuhkan: %s",
                    settlement.getMerchantId(), settlementId, merchantAccount.getBalance(), net));
        }

        JournalTransaction payout = JournalTransaction.builder()
                .idempotencyKey("SETTLEMENT-PAYOUT-" + settlementId)
                .referenceType(ReferenceType.SETTLEMENT)
                .description(String.format("Payout settlement %s merchant %s periode %s s/d %s",
                        settlementId, settlement.getMerchantId(),
                        settlement.getPeriodStart(), settlement.getPeriodEnd()))
                .build();
        payout = journalTransactionRepository.save(payout);

        payout.addLedgerEntry(LedgerEntry.builder()
                .journalTransaction(payout)
                .account(merchantAccount)
                .entryType(EntryType.DEBIT)
                .amount(net)
                .build());
        payout.addLedgerEntry(LedgerEntry.builder()
                .journalTransaction(payout)
                .account(clearingAccount)
                .entryType(EntryType.CREDIT)
                .amount(net)
                .build());

        merchantAccount.setBalance(merchantAccount.getBalance().subtract(net));
        clearingAccount.setBalance(clearingAccount.getBalance().add(net));

        accountRepository.save(merchantAccount);
        accountRepository.save(clearingAccount);
        journalTransactionRepository.save(payout);

        settlement.setStatus(SettlementStatus.PAID);
        settlement.setPayoutTransactionId(payout.getId());
        settlement.setPaidAt(LocalDateTime.now());
        MerchantSettlement saved = settlementRepository.save(settlement);

        metrics.settlementPaid(saved.getCurrency().name(), net);
        log.info("Settlement {} dibayar: payout={}, net={}, merchant={}",
                settlementId, payout.getId(), net, settlement.getMerchantId());

        return toResponse(saved, true);
    }

    @Override
    @Transactional
    public SettlementResponse cancel(UUID settlementId, String reason) {
        MerchantSettlement settlement = settlementRepository.findByIdForUpdate(settlementId)
                .orElseThrow(() -> new EntityNotFoundException("Settlement tidak ditemukan: " + settlementId));

        if (!settlement.isCancellable()) {
            throw new ResourceConflictException(String.format(
                    "Settlement %s berstatus %s dan tidak dapat dibatalkan", settlementId, settlement.getStatus()));
        }

        settlement.setStatus(SettlementStatus.CANCELLED);
        settlement.setCancelledAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            settlement.setNotes(reason);
        }
        MerchantSettlement saved = settlementRepository.save(settlement);

        metrics.settlementCancelled(saved.getCurrency().name());
        log.info("Settlement {} dibatalkan: {}", settlementId, reason);
        return toResponse(saved, true);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Menghitung snapshot settlement dari ledger merchant.
     * <p>
     * Hanya jurnal PAYMENT (credit merchant) dan REFUND yang membalik PAYMENT
     * (debit merchant) yang dihitung. Semua angka dinormalisasi ke skala 4.
     */
    private Computation compute(String merchantId, Currency currency,
                                LocalDateTime periodStart, LocalDateTime periodEnd,
                                BigDecimal feePercent) {

        Optional<Account> merchantAccount = accountRepository
                .findAccountByOwnerIdAndCurrencyCustom(merchantId, currency);

        List<SettlementContribution> contributions = merchantAccount
                .map(account -> ledgerEntryRepository.findSettlementContributions(
                        account.getId(), periodStart, periodEnd, ReferenceType.PAYMENT, ReferenceType.REFUND))
                .orElseGet(List::of);

        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        int paymentCount = 0;
        int refundCount = 0;

        for (SettlementContribution contribution : contributions) {
            BigDecimal signed = contribution.entryType() == EntryType.CREDIT
                    ? contribution.amount() : contribution.amount().negate();

            if (contribution.referenceType() == ReferenceType.PAYMENT) {
                gross = gross.add(signed);
                paymentCount++;
            } else {
                refund = refund.add(signed.negate());
                refundCount++;
            }
        }

        gross = normalize(gross);
        refund = normalize(refund);

        BigDecimal feeBase = gross.subtract(refund);
        BigDecimal fee = feeBase.signum() > 0
                ? feeBase.multiply(feePercent).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal net = normalize(feeBase.subtract(fee));

        return new Computation(contributions, gross, refund, fee, net, paymentCount, refundCount);
    }

    private record Computation(List<SettlementContribution> contributions,
                               BigDecimal gross,
                               BigDecimal refund,
                               BigDecimal fee,
                               BigDecimal net,
                               int paymentCount,
                               int refundCount) {
    }

    private void validatePeriod(LocalDateTime periodStart, LocalDateTime periodEnd) {
        if (periodStart == null || periodEnd == null) {
            throw new IllegalArgumentException("periodStart dan periodEnd wajib diisi");
        }
        if (!periodEnd.isAfter(periodStart)) {
            throw new IllegalArgumentException("periodEnd harus setelah periodStart");
        }
    }

    private BigDecimal resolveFeePercent(BigDecimal requested) {
        BigDecimal feePercent = (requested != null ? requested : settlementProperties.feePercent())
                .setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
        if (feePercent.signum() < 0 || feePercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("feePercent harus bernilai 0..100, diterima: " + requested);
        }
        return feePercent;
    }

    private BigDecimal normalize(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Advisory lock transaksi PostgreSQL per merchant+currency. Dilepas otomatis
     * saat transaksi selesai, sehingga aman meski request gagal di tengah jalan.
     */
    private void acquireMerchantLock(String merchantId, Currency currency) {
        String key = "settlement:" + merchantId + ":" + currency;
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(?1))")
                .setParameter(1, key)
                .getSingleResult();
    }

    private MerchantSettlement findSettlement(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new EntityNotFoundException("Settlement tidak ditemukan: " + settlementId));
    }

    private SettlementItem toItem(SettlementContribution contribution) {
        BigDecimal signed = contribution.entryType() == EntryType.CREDIT
                ? contribution.amount() : contribution.amount().negate();

        return SettlementItem.builder()
                .transactionId(contribution.transactionId())
                .referenceType(contribution.referenceType())
                .entryType(contribution.entryType())
                .amount(contribution.amount())
                .signedAmount(signed)
                .occurredAt(contribution.occurredAt())
                .build();
    }

    private Account getOrCreateClearingAccount(Currency currency) {
        return accountRepository.findAccountByOwnerIdAndCurrencyCustom(
                        settlementProperties.clearingOwnerId(), currency)
                .orElseGet(() -> accountRepository.save(Account.builder()
                        .ownerId(settlementProperties.clearingOwnerId())
                        .accountName("Settlement Clearing - " + currency)
                        .accountType(AccountType.ASSET)
                        .currency(currency)
                        .balance(BigDecimal.ZERO)
                        .build()));
    }

    /**
     * Mengunci akun dengan {@code SELECT ... FOR UPDATE} dalam urutan UUID menaik
     * agar konsisten dengan jalur checkout/refund dan bebas deadlock.
     */
    private void lockAccountsForUpdate(Account... accounts) {
        List<Account> targets = Arrays.stream(accounts)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(Account::getId))
                .toList();

        accountRepository.flush();
        targets.forEach(accountRepository::lockForUpdate);
    }

    private void ensureActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(String.format(
                    "Akun %s (%s) berstatus %s dan tidak dapat bertransaksi",
                    account.getId(), account.getOwnerId(), account.getStatus()));
        }
    }

    private SettlementStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SettlementStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(String.format(
                    "Nilai status tidak valid: '%s'. Pilihan: %s",
                    raw, Arrays.toString(SettlementStatus.values())));
        }
    }

    private SettlementResponse toResponse(MerchantSettlement settlement, boolean includeItems) {
        long itemCount = includeItems
                ? settlement.getItems().size()
                : settlementItemRepository.countBySettlementId(settlement.getId());

        return SettlementResponse.builder()
                .id(settlement.getId())
                .merchantId(settlement.getMerchantId())
                .currency(settlement.getCurrency())
                .periodStart(settlement.getPeriodStart())
                .periodEnd(settlement.getPeriodEnd())
                .grossAmount(settlement.getGrossAmount())
                .refundAmount(settlement.getRefundAmount())
                .feePercent(settlement.getFeePercent())
                .feeAmount(settlement.getFeeAmount())
                .netAmount(settlement.getNetAmount())
                .status(settlement.getStatus())
                .payoutTransactionId(settlement.getPayoutTransactionId())
                .idempotencyKey(settlement.getIdempotencyKey())
                .notes(settlement.getNotes())
                .itemCount(itemCount)
                .version(settlement.getVersion())
                .createdAt(settlement.getCreatedAt())
                .updatedAt(settlement.getUpdatedAt())
                .paidAt(settlement.getPaidAt())
                .cancelledAt(settlement.getCancelledAt())
                .items(includeItems
                        ? settlement.getItems().stream().map(this::toItemResponse).toList()
                        : null)
                .build();
    }

    private SettlementItemResponse toItemResponse(SettlementItem item) {
        return SettlementItemResponse.builder()
                .id(item.getId())
                .transactionId(item.getTransactionId())
                .referenceType(item.getReferenceType())
                .entryType(item.getEntryType())
                .amount(item.getAmount())
                .signedAmount(item.getSignedAmount())
                .occurredAt(item.getOccurredAt())
                .build();
    }
}
