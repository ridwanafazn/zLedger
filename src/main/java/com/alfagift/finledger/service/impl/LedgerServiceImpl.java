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
import com.alfagift.finledger.dto.PageResponse;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

        // 1. Ambil atau buat akaun pelanggan (LIABILITY — wang pelanggan adalah kewajiban sistem)
        Account customerAccount = getOrCreateCustomerAccount(request.getOwnerId(), request.getCurrency());

        // 2. Ambil akaun merchant (REVENUE)
        Account merchantAccount = getOrCreateMerchantAccount(request.getMerchantId(), request.getCurrency());

        // 3. Kunci kedua akaun dengan urutan deterministik (cegah deadlock) dan
        //    pastikan statusnya ACTIVE sebelum mengubah saldo.
        lockAccountsForUpdate(customerAccount, merchantAccount);
        ensureActive(customerAccount);
        ensureActive(merchantAccount);

        // 4. Validasi balans pelanggan mencukupi untuk total pembayaran
        if (customerAccount.getBalance().compareTo(request.getTotalAmount()) < 0) {
            throw new InsufficientBalanceException(
                    String.format("Balans %s tidak mencukupi. Tersedia: %s, Diperlukan: %s",
                            request.getCurrency(), customerAccount.getBalance(), request.getTotalAmount()));
        }

        // 5. Buat JournalTransaction
        JournalTransaction journal = JournalTransaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .referenceType(ReferenceType.PAYMENT)
                .description(String.format("Pembayaran multi-tender untuk owner %s kepada merchant %s",
                        request.getOwnerId(), request.getMerchantId()))
                .build();
        journal = journalTransactionRepository.save(journal);

        // 6. Buat entri ledger: DEBIT pelanggan, CREDIT merchant
        LedgerEntry debitEntry = LedgerEntry.builder()
                .journalTransaction(journal)
                .account(customerAccount)
                .entryType(EntryType.DEBIT)
                .amount(request.getTotalAmount())
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .journalTransaction(journal)
                .account(merchantAccount)
                .entryType(EntryType.CREDIT)
                .amount(request.getTotalAmount())
                .build();

        journal.addLedgerEntry(debitEntry);
        journal.addLedgerEntry(creditEntry);

        // 7. Kemaskini balans
        customerAccount.setBalance(customerAccount.getBalance().subtract(request.getTotalAmount()));
        merchantAccount.setBalance(merchantAccount.getBalance().add(request.getTotalAmount()));

        accountRepository.save(customerAccount);
        accountRepository.save(merchantAccount);
        journalTransactionRepository.save(journal);

        // 8. Kirim Kafka event jika A_Poin terlibat dalam tenders
        boolean involvesAPoin = request.getTenders().stream()
                .anyMatch(t -> t.getCurrency() == Currency.A_POIN);

        if (involvesAPoin) {
            PointsAwardedEvent event = PointsAwardedEvent.builder()
                    .transactionId(journal.getId().toString())
                    .ownerId(request.getOwnerId())
                    .currency(Currency.A_POIN)
                    .amount(request.getTotalAmount())
                    .awardedAt(LocalDateTime.now())
                    .description("Hadiah A_Poin untuk pembayaran multi-tender")
                    .build();
            // Ditulis ke outbox dalam transaksi yang sama; relay akan mengirim ke Kafka
            outboxService.enqueue("JOURNAL_TRANSACTION", journal.getId().toString(),
                    "POINTS_AWARDED", event);
        }

        return CheckoutResponse.builder()
                .transactionId(journal.getId())
                .ownerId(request.getOwnerId())
                .merchantId(request.getMerchantId())
                .currency(request.getCurrency())
                .totalAmount(request.getTotalAmount())
                .balanceRemaining(customerAccount.getBalance())
                .processedAt(LocalDateTime.now())
                .status("SUCCESS")
                .build();
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
                .processedAt(LocalDateTime.now())
                .status("SUCCESS")
                .build();
    }

    @Override
    public List<LedgerEntry> getAccountMutations(UUID accountId) {
        return ledgerEntryRepository.findByAccountIdOrderByCreatedAtDesc(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TransactionSummaryResponse> listTransactions(int page, int size, String referenceType) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<JournalTransaction> transactions = (referenceType == null || referenceType.isBlank())
                ? journalTransactionRepository.findAll(pageable)
                : journalTransactionRepository.findByReferenceType(parseReferenceType(referenceType), pageable);

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