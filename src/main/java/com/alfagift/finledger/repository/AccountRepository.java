package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.Currency;
import com.alfagift.finledger.domain.enums.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository untuk entiti {@link Account}.
 * <p>
 * Menyediakan kaedah kunci pessimistik untuk mencegah race condition
 * semasa transaksi concurrency tinggi (contoh: flash sale).
 */
public interface AccountRepository extends JpaRepository<Account, UUID>, JpaSpecificationExecutor<Account>, AccountRepositoryCustom {

    /**
     * Mencari akaun berdasarkan pemilik dan mata wang.
     * Didelegasikan ke custom implementation untuk handle enum dengan benar.
     */
    Optional<Account> findAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency);

    /**
     * Menentukan wujudnya akaun berdasarkan pemilik dan mata wang.
     */
    boolean existsAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency);

    /**
     * Akun yang cached balance-nya tidak sama dengan saldo ekspektasi dari ledger:
     * {@code opening_balance + total CREDIT - total DEBIT}.
     * <p>
     * Dipakai oleh rekonsiliasi (Fase I). Query JPQL agar tetap portabel dan
     * memakai entity yang sama dengan jalur transaksi.
     *
     * @param credit konstanta enum {@link EntryType#CREDIT}
     */
    @Query("""
            select new com.alfagift.finledger.repository.AccountBalanceCheck(
                       a,
                       coalesce(sum(case when le.entryType = :credit then le.amount else -le.amount end), 0))
              from Account a
              left join a.ledgerEntries le
             group by a
            having a.balance <> a.openingBalance
                     + coalesce(sum(case when le.entryType = :credit then le.amount else -le.amount end), 0)
            """)
    List<AccountBalanceCheck> findBalanceMismatches(@Param("credit") EntryType credit);
}
