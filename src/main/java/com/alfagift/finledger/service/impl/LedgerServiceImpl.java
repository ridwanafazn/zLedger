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
import com.alfagift.finledger.dto.MutationResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.dto.ReversalResponse;
import com.alfagift.finledger.dto.TransactionDetailResponse;
import com.alfagift.finledger.dto.TransactionSummaryResponse;
import com.alfagift.finledger.infrastructure.AccountInactiveException;
import com.alfagift.finledger.infrastructure.Idempotent;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.outbox.OutboxService;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import io.micrometer.core.annotation.Timed;
import jakarta.persistence.EntityNotFoundException;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementasi {@link LedgerService} dengan double-entry ledger yang immutable.
 * <p>
 * Alur {@link #processMultiTenderPayment}:
 * <ol>
 *   <li>Kunci akaun pelanggan dan merchant secara pessimistik</li>
 *   <li>Validasi balans pelanggan mencukupi untuk setiap tender</li>
 *   <li>Buat {@link JournalTransaction}</li>
 *   <li>Buat {@link LedgerEntry} debit (pelanggan) dan credit (merchant) — jumlah DEBIT = CREDIT</li>
 *   <li>Kemaskini balans akaun</li>
 *   <li>Kirim Kafka event jika A_Poin terlibat</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LedgerServiceImpl implements LedgerService {

    private final AccountRepository accountRepository;
    private final JournalTransactionRepository journalTransactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxService outboxService;

    @Override
    @Transactional
    @Idempotent
    @Timed(value = "ledger.checkout", description = "Latency proses checkout multi-tender")
    public CheckoutResponse processMultiTenderPayment(CheckoutRequest request) {
        log.info("Memproses pembayaran multi-tender untuk ownerId={}, total={} {}",
                request.getOwnerId(), request.getTotalAmount(), request.getCurrency());

        // 1. Tender adalah sumber kebenaran pembagian saldo, bukan field currency.
        Map<Currency, BigDecimal> tenderByCurrency = groupTendersByCurrency(request);
        validateTenderTotal(request, tenderByCurrency);

        // 2. Ambil atau buat akaun pelanggan + merchant untuk SETIAP mata uang yang terlibat.
        Map<Currency, Account> customerAccounts = new LinkedHashMap<>();
        Map<Currency, Account> merchantAccounts = new LinkedHashMap<>();
        for (Currency currency : tenderByCurrency.keySet()) {
            customerAccounts.put(currency, getOrCreateCustomerAccount(request.getOwnerId(), currency));
            merchantAccounts.put(currency, getOrCreateMerchantAccount(request.getMerchantId(), currency));
        }

        // 3. Kunci semua akaun dalam urutan deterministik (cegah deadlock).
        List<Account> accountsToLock = new ArrayList<>();
        accountsToLock.addAll(customerAccounts.values());
        accountsToLock.addAll(merchantAccounts.values());
        lockAccountsForUpdate(accountsToLock.toArray(new Account[0]));

        // 4. Validasi status + saldo per mata uang sebelum menulis apa pun.
        for (Map.Entry<Currency, BigDecimal> entry : tenderByCurrency.entrySet()) {
            Account customer = customerAccounts.get(entry.getKey());
            Account merchant = merchantAccounts.get(entry.getKey());

            ensureActive(customer);
            ensureActive(merchant);

            if (customer.getBalance().compareTo(entry.getValue()) < 0) {
                throw new InsufficientBalanceException(
                        String.format("Balans %s tidak mencukupi. Tersedia: %s, Diperlukan: %s",
                                entry.getKey(), customer.getBalance(), entry.getValue()));
            }
        }

        // 5. Buat JournalTransaction
        JournalTransaction journal = JournalTransaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .referenceType(ReferenceType.PAYMENT)
                .description(String.format("Pembayaran multi-tender untuk owner %s kepada merchant %s",
                        request.getOwnerId(), request.getMerchantId()))
                .build();
        journal = journalTransactionRepository.save(journal);

        // 6. Satu pasang DEBIT/CREDIT per mata uang, lalu update saldo masing-masing.
        Map<Currency, BigDecimal> balances = new LinkedHashMap<>();
        for (Map.Entry<Currency, BigDecimal> entry : tenderByCurrency.entrySet()) {
            Currency currency = entry.getKey();
            BigDecimal amount = entry.getValue();
            Account customer = customerAccounts.get(currency);
            Account merchant = merchantAccounts.get(currency);

            journal.addLedgerEntry(LedgerEntry.builder()
                    .journalTransaction(journal)
                    .account(customer)
                    .entryType(EntryType.DEBIT)
                    .amount(amount)
                    .build());

            journal.addLedgerEntry(LedgerEntry.builder()
                    .journalTransaction(journal)
                    .account(merchant)
                    .entryType(EntryType.CREDIT)
                    .amount(amount)
                    .build());

            customer.setBalance(customer.getBalance().subtract(amount));
            merchant.setBalance(merchant.getBalance().add(amount));

            accountRepository.save(customer);
            accountRepository.save(merchant);

            balances.put(currency, customer.getBalance());
        }

        journalTransactionRepository.save(journal);

        // 7. Kirim Kafka event jika A_Poin terlibat dalam tenders
        if (tenderByCurrency.containsKey(Currency.A_POIN)) {
            PointsAwardedEvent event = PointsAwardedEvent.builder()
                    .transactionId(journal.getId().toString())
                    .ownerId(request.getOwnerId())
                    .currency(Currency.A_POIN)
                    .amount(tenderByCurrency.get(Currency.A_POIN))
                    .awardedAt(LocalDateTime.now())
                    .description("Hadiah A_Poin untuk pembayaran multi-tender")
                    .build();
            // Ditulis ke outbox dalam transaksi yang sama; relay akan mengirim ke Kafka
            outboxService.enqueue("JOURNAL_TRANSACTION", journal.getId().toString(),
                    "POINTS_AWARDED", event);
        }

        Currency primaryCurrency = resolvePrimaryCurrency(request, tenderByCurrency);

        return CheckoutResponse.builder()
                .transactionId(journal.getId())
                .ownerId(request.getOwnerId())
                .merchantId(request.getMerchantId())
                .currency(primaryCurrency)
                .totalAmount(request.getTotalAmount())
                .balanceRemaining(balances.get(primaryCurrency))
                .balances(balances)
                .processedAt(LocalDateTime.now())
                .status("SUCCESS")
                .build();
    }

    /**
     * Mengelompokkan tender per mata uang. Tender dengan mata uang sama dijumlahkan.
     */
    private Map<Currency, BigDecimal> groupTendersByCurrency(CheckoutRequest request) {
        if (request.getTenders() == null || request.getTenders().isEmpty()) {
            throw new IllegalArgumentException("Minimal satu tender wajib disediakan");
        }

        Map<Currency, BigDecimal> grouped = new LinkedHashMap<>();
        for (CheckoutRequest.TenderDetail tender : request.getTenders()) {
            if (tender.getCurrency() == null || tender.getAmount() == null) {
                throw new IllegalArgumentException("Setiap tender wajib memiliki currency dan amount");
            }
            if (tender.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Jumlah tender harus lebih besar dari nol");
            }
            grouped.merge(tender.getCurrency(), tender.getAmount(), BigDecimal::add);
        }
        return grouped;
    }

    /**
     * Memastikan total tender sama dengan totalAmount agar tidak ada selisih
     * yang tidak tercatat di ledger.
     */
    private void validateTenderTotal(CheckoutRequest request, Map<Currency, BigDecimal> tenderByCurrency) {
        BigDecimal tenderTotal = tenderByCurrency.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (tenderTotal.compareTo(request.getTotalAmount()) != 0) {
            throw new IllegalArgumentException(String.format(
                    "Total tender (%s) tidak sama dengan totalAmount (%s)",
                    tenderTotal, request.getTotalAmount()));
        }
    }

    /**
     * Mata uang utama untuk response: pakai request.currency bila terlibat,
     * jika tidak pakai mata uang tender pertama.
     */
    private Currency resolvePrimaryCurrency(CheckoutRequest request, Map<Currency, BigDecimal> tenderByCurrency) {
        Currency requested = request.getCurrency();
        if (requested != null && tenderByCurrency.containsKey(requested)) {
            return requested;
        }
        return tenderByCurrency.keySet().iterator().next();
    }

    @Override
    @Transactional
    @Idempotent
    @Timed(value = "ledger.redeem", description = "Latency redeem flash sale")
    public CheckoutResponse redeemFlashSale(String ownerId, UUID voucherId, Integer points, String idempotencyKey) {
        log.info("Flash sale redemption: ownerId={}, voucherId={}, points={}", ownerId, voucherId, points);

        Account customerAccount = getOrCreateCustomerAccount(ownerId, Currency.A_POIN);

        // Kunci akaun pelanggan + pool promosi dengan urutan deterministik
        Account promoPool = getOrCreatePromoPool();
        lockAccountsForUpdate(customerAccount, promoPool);
        ensureActive(customerAccount);
        ensureActive(promoPool);

        BigDecimal pointsBigDecimal = BigDecimal.valueOf(points);
        if (customerAccount.getBalance().compareTo(pointsBigDecimal) < 0) {
            throw new InsufficientBalanceException(
                    String.format("A_Poin tidak mencukupi. Tersedia: %s, Diperlukan: %s",
                            customerAccount.getBalance(), pointsBigDecimal));
        }

        JournalTransaction journal = JournalTransaction.builder()
                .idempotencyKey(idempotencyKey)
                .referenceType(ReferenceType.REDEMPTION)
                .description(String.format("Flash sale redemption voucher %s", voucherId))
                .build();
        journal = journalTransactionRepository.save(journal);

        LedgerEntry debitEntry = LedgerEntry.builder()
                .journalTransaction(journal)
                .account(customerAccount)
                .entryType(EntryType.DEBIT)
                .amount(pointsBigDecimal)
                .build();

        // System promo pool (EXPENSE) menerima credit
        LedgerEntry creditEntry = LedgerEntry.builder()
                .journalTransaction(journal)
                .account(promoPool)
                .entryType(EntryType.CREDIT)
                .amount(pointsBigDecimal)
                .build();

        journal.addLedgerEntry(debitEntry);
        journal.addLedgerEntry(creditEntry);

        customerAccount.setBalance(customerAccount.getBalance().subtract(pointsBigDecimal));
        promoPool.setBalance(promoPool.getBalance().add(pointsBigDecimal));

        accountRepository.save(customerAccount);
        accountRepository.save(promoPool);
        journalTransactionRepository.save(journal);

        return CheckoutResponse.builder()
                .transactionId(journal.getId())
                .ownerId(ownerId)
                .merchantId("system")
                .currency(Currency.A_POIN)
                .totalAmount(pointsBigDecimal)
                .balanceRemaining(customerAccount.getBalance())
                .balances(Map.of(Currency.A_POIN, customerAccount.getBalance()))
                .processedAt(LocalDateTime.now())
                .status("SUCCESS")
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MutationResponse> getAccountMutations(UUID accountId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<LedgerEntry> mutations = ledgerEntryRepository.findByAccountId(accountId, pageable);

        List<MutationResponse> content = mutations.getContent().stream()
                .map(entry -> MutationResponse.builder()
                        .id(entry.getId())
                        .transactionId(entry.getJournalTransaction().getId())
                        .accountId(entry.getAccount().getId())
                        .entryType(entry.getEntryType())
                        .amount(entry.getAmount())
                        .description(entry.getJournalTransaction().getDescription())
                        .createdAt(entry.getCreatedAt())
                        .build())
                .toList();

        return PageResponse.<MutationResponse>builder()
                .content(content)
                .page(PageResponse.PageMetadata.builder()
                        .number(mutations.getNumber())
                        .size(mutations.getSize())
                        .totalElements(mutations.getTotalElements())
                        .totalPages(mutations.getTotalPages())
                        .first(mutations.isFirst())
                        .last(mutations.isLast())
                        .numberOfElements(mutations.getNumberOfElements())
                        .build())
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransactionSummaryResponse> listTransactions(
            int page, int size, String referenceType, UUID accountId,
            java.time.LocalDate from, java.time.LocalDate to) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<JournalTransaction> specification = buildTransactionSpecification(
                referenceType, accountId, from, to);

        Page<JournalTransaction> transactions = journalTransactionRepository.findAll(specification, pageable);

        List<TransactionSummaryResponse> content = transactions.getContent().stream()
                .map(this::toSummary)
                .toList();

        PageResponse.PageMetadata metadata = PageResponse.PageMetadata.builder()
                .number(transactions.getNumber())
                .size(transactions.getSize())
                .totalElements(transactions.getTotalElements())
                .totalPages(transactions.getTotalPages())
                .first(transactions.isFirst())
                .last(transactions.isLast())
                .numberOfElements(transactions.getNumberOfElements())
                .build();

        return PageResponse.<TransactionSummaryResponse>builder()
                .content(content)
                .page(metadata)
                .sort(List.of(PageResponse.SortInfo.builder()
                        .field("createdAt")
                        .direction("DESC")
                        .build()))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(UUID transactionId) {
        JournalTransaction journal = journalTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Transaksi tidak ditemukan: " + transactionId));

        List<LedgerEntry> entries = ledgerEntryRepository.findByJournalTransactionId(transactionId);

        BigDecimal totalDebit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalCredit = entries.stream()
                .filter(e -> e.getEntryType() == EntryType.CREDIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<TransactionDetailResponse.Entry> entryDtos = entries.stream()
                .map(e -> TransactionDetailResponse.Entry.builder()
                        .id(e.getId())
                        .accountId(e.getAccount().getId())
                        .ownerId(e.getAccount().getOwnerId())
                        .accountType(e.getAccount().getAccountType())
                        .currency(e.getAccount().getCurrency())
                        .entryType(e.getEntryType())
                        .amount(e.getAmount())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();

        return TransactionDetailResponse.builder()
                .id(journal.getId())
                .idempotencyKey(journal.getIdempotencyKey())
                .referenceType(journal.getReferenceType())
                .description(journal.getDescription())
                .createdAt(journal.getCreatedAt())
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .balanced(totalDebit.compareTo(totalCredit) == 0)
                .entries(entryDtos)
                .build();
    }

    @Override
    @Transactional
    @Idempotent
    public ReversalResponse reverseTransaction(UUID transactionId, String reason) {
        JournalTransaction original = journalTransactionRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Transaksi tidak ditemukan: " + transactionId));

        if (original.getReferenceType() == ReferenceType.REFUND) {
            throw new IllegalArgumentException("Transaksi refund tidak dapat dibalik lagi");
        }
        if (journalTransactionRepository.existsByReversalOfId(transactionId)) {
            throw new IllegalArgumentException("Transaksi " + transactionId + " sudah pernah dibalik");
        }

        List<LedgerEntry> originalEntries = ledgerEntryRepository.findByJournalTransactionId(transactionId);
        if (originalEntries.isEmpty()) {
            throw new IllegalArgumentException("Transaksi tidak memiliki entri ledger");
        }

        List<Account> accounts = originalEntries.stream()
                .map(LedgerEntry::getAccount)
                .distinct()
                .toList();
        lockAccountsForUpdate(accounts.toArray(new Account[0]));

        JournalTransaction reversal = JournalTransaction.builder()
                .idempotencyKey("REVERSAL-" + transactionId)
                .referenceType(ReferenceType.REFUND)
                .reversalOfId(transactionId)
                .description(String.format("Reversal untuk transaksi %s: %s", transactionId, reason))
                .build();
        reversal = journalTransactionRepository.save(reversal);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;

        for (LedgerEntry entry : originalEntries) {
            EntryType compensatingType = entry.getEntryType() == EntryType.DEBIT
                    ? EntryType.CREDIT : EntryType.DEBIT;

            LedgerEntry compensating = LedgerEntry.builder()
                    .journalTransaction(reversal)
                    .account(entry.getAccount())
                    .entryType(compensatingType)
                    .amount(entry.getAmount())
                    .build();
            reversal.addLedgerEntry(compensating);

            if (compensatingType == EntryType.DEBIT) {
                totalDebit = totalDebit.add(entry.getAmount());
                entry.getAccount().setBalance(entry.getAccount().getBalance().subtract(entry.getAmount()));
            } else {
                totalCredit = totalCredit.add(entry.getAmount());
                entry.getAccount().setBalance(entry.getAccount().getBalance().add(entry.getAmount()));
            }
        }

        accounts.forEach(accountRepository::save);
        journalTransactionRepository.save(reversal);

        log.info("Transaksi {} dibalik oleh {} (total debit={}, credit={})",
                transactionId, reversal.getId(), totalDebit, totalCredit);

        return ReversalResponse.builder()
                .originalTransactionId(transactionId)
                .reversalTransactionId(reversal.getId())
                .referenceType(ReferenceType.REFUND)
                .totalDebit(totalDebit)
                .totalCredit(totalCredit)
                .reason(reason)
                .processedAt(LocalDateTime.now())
                .status("REVERSED")
                .build();
    }

    private Specification<JournalTransaction> buildTransactionSpecification(
            String referenceType, UUID accountId, java.time.LocalDate from, java.time.LocalDate to) {

        ReferenceType type = (referenceType == null || referenceType.isBlank())
                ? null : parseReferenceType(referenceType);
        LocalDateTime fromTime = from != null ? from.atStartOfDay() : null;
        LocalDateTime toTime = to != null ? to.atTime(23, 59, 59) : null;

        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();

            if (type != null) {
                predicates.add(cb.equal(root.get("referenceType"), type));
            }
            if (fromTime != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTime));
            }
            if (toTime != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), toTime));
            }
            if (accountId != null && query != null) {
                var subquery = query.subquery(UUID.class);
                var entryRoot = subquery.from(LedgerEntry.class);
                subquery.select(entryRoot.get("journalTransaction").get("id"))
                        .where(cb.equal(entryRoot.get("account").get("id"), accountId));
                predicates.add(root.get("id").in(subquery));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private TransactionSummaryResponse toSummary(JournalTransaction journal) {
        BigDecimal totalAmount = journal.getLedgerEntries().stream()
                .filter(e -> e.getEntryType() == EntryType.DEBIT)
                .map(LedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return TransactionSummaryResponse.builder()
                .id(journal.getId())
                .idempotencyKey(journal.getIdempotencyKey())
                .referenceType(journal.getReferenceType())
                .description(journal.getDescription())
                .createdAt(journal.getCreatedAt())
                .entryCount(journal.getLedgerEntries().size())
                .totalAmount(totalAmount)
                .build();
    }

    private ReferenceType parseReferenceType(String raw) {
        try {
            return ReferenceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(String.format(
                    "Nilai referenceType tidak valid: '%s'. Pilihan yang tersedia: %s",
                    raw, java.util.Arrays.toString(ReferenceType.values())));
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helper methods                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Mengunci akaun dengan {@code SELECT ... FOR UPDATE} dalam urutan UUID menaik.
     * <p>
     * Urutan yang konsisten untuk semua transaksi mencegah deadlock ketika dua
     * transaksi menyentuh pasangan akaun yang sama dengan arah berbeda.
     * <p>
     * {@code flush()} diperlukan agar akaun yang baru dibuat pada transaksi ini
     * sudah memiliki row sebelum di-refresh dengan pessimistic lock (Hibernate
     * sudah mengisi {@code version=0} sebelum insert).
     */
    private void lockAccountsForUpdate(Account... accounts) {
        List<Account> targets = java.util.Arrays.stream(accounts)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted(java.util.Comparator.comparing(Account::getId))
                .toList();

        if (targets.isEmpty()) {
            return;
        }

        accountRepository.flush();
        targets.forEach(accountRepository::lockForUpdate);
    }

    /**
     * Menolak transaksi bila akaun tidak berstatus ACTIVE.
     */
    private void ensureActive(Account account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountInactiveException(String.format(
                    "Akun %s (%s) berstatus %s dan tidak dapat bertransaksi",
                    account.getId(), account.getOwnerId(), account.getStatus()));
        }
    }

    private Account getOrCreateCustomerAccount(String ownerId, Currency currency) {
        return accountRepository.findAccountByOwnerIdAndCurrencyCustom(ownerId, currency)
                .orElseGet(() -> {
                    Account acc = Account.builder()
                            .ownerId(ownerId)
                            .accountName(String.format("Dompet %s - %s", ownerId, currency))
                            .accountType(AccountType.LIABILITY)
                            .currency(currency)
                            .balance(BigDecimal.ZERO)
                            .build();
                    return accountRepository.save(acc);
                });
    }

    private Account getOrCreateMerchantAccount(String merchantId, Currency currency) {
        return accountRepository.findAccountByOwnerIdAndCurrencyCustom(merchantId, currency)
                .orElseGet(() -> {
                    Account acc = Account.builder()
                            .ownerId(merchantId)
                            .accountName(String.format("Dompet Merchant %s - %s", merchantId, currency))
                            .accountType(AccountType.REVENUE)
                            .currency(currency)
                            .balance(BigDecimal.ZERO)
                            .build();
                    return accountRepository.save(acc);
                });
    }

    private Account getOrCreatePromoPool() {
        return accountRepository.findAccountByOwnerIdAndCurrencyCustom("system", Currency.A_POIN)
                .orElseGet(() -> {
                    Account acc = Account.builder()
                            .ownerId("system")
                            .accountName("Pool Promosi Sistem - A_POIN")
                            .accountType(AccountType.EXPENSE)
                            .currency(Currency.A_POIN)
                            .balance(BigDecimal.ZERO)
                            .build();
                    return accountRepository.save(acc);
                });
    }
}