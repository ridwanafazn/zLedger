package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
}