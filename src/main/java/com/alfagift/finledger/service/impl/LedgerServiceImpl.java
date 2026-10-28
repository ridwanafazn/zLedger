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
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import com.alfagift.finledger.infrastructure.kafka.LoyaltyPointsProducer;
import com.alfagift.finledger.repository.AccountRepository;
import com.alfagift.finledger.repository.JournalTransactionRepository;
import com.alfagift.finledger.repository.LedgerEntryRepository;
import com.alfagift.finledger.service.LedgerService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final LoyaltyPointsProducer loyaltyPointsProducer;

    @Override
    @Transactional
    public CheckoutResponse processMultiTenderPayment(CheckoutRequest request) {
        log.info("Memproses pembayaran multi-tender untuk ownerId={}, total={} {}",
                request.getOwnerId(), request.getTotalAmount(), request.getCurrency());

        // 1. Ambil atau buat akaun pelanggan (LIABILITY — wang pelanggan adalah kewajiban sistem)
        Account customerAccount = getOrCreateCustomerAccount(request.getOwnerId(), request.getCurrency());

        // 2. Ambil akaun merchant (REVENUE)
        Account merchantAccount = getOrCreateMerchantAccount(request.getMerchantId(), request.getCurrency());

        // 3. Validasi balans pelanggan mencukupi untuk total pembayaran
        if (customerAccount.getBalance().compareTo(request.getTotalAmount()) < 0) {
            throw new InsufficientBalanceException(
                    String.format("Balans %s tidak mencukupi. Tersedia: %s, Diperlukan: %s",
                            request.getCurrency(), customerAccount.getBalance(), request.getTotalAmount()));
        }

        // 4. Buat JournalTransaction
        JournalTransaction journal = JournalTransaction.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .referenceType(ReferenceType.PAYMENT)
                .description(String.format("Pembayaran multi-tender untuk owner %s kepada merchant %s",
                        request.getOwnerId(), request.getMerchantId()))
                .build();
        journal = journalTransactionRepository.save(journal);

        // 5. Buat entri ledger: DEBIT pelanggan, CREDIT merchant
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

        // 6. Kemaskini balans
        customerAccount.setBalance(customerAccount.getBalance().subtract(request.getTotalAmount()));
        merchantAccount.setBalance(merchantAccount.getBalance().add(request.getTotalAmount()));

        accountRepository.save(customerAccount);
        accountRepository.save(merchantAccount);
        journalTransactionRepository.save(journal);

        // 7. Kirim Kafka event jika A_Poin terlibat dalam tenders
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
            loyaltyPointsProducer.sendPointsAwarded(event);
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
    public CheckoutResponse redeemFlashSale(String ownerId, UUID voucherId, Integer points, String idempotencyKey) {
        log.info("Flash sale redemption: ownerId={}, voucherId={}, points={}", ownerId, voucherId, points);

        Account customerAccount = getOrCreateCustomerAccount(ownerId, Currency.A_POIN);

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
        Account promoPool = getOrCreatePromoPool();
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

    /* ------------------------------------------------------------------ */
    /* Helper methods                                                     */
    /* ------------------------------------------------------------------ */

    private Account getOrCreateCustomerAccount(String ownerId, Currency currency) {
        return accountRepository.findByOwnerIdAndCurrency(ownerId, currency)
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
        return accountRepository.findByOwnerIdAndCurrency(merchantId, currency)
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
        return accountRepository.findByOwnerIdAndCurrency("system", Currency.A_POIN)
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