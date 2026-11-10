package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.Currency;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom repository interface for Account entity.
 * Handles custom queries that cannot be expressed with Spring Data JPA derived queries.
 */
public interface AccountRepositoryCustom {

    Optional<Account> findAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency);

    boolean existsAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency);

    /**
     * Mengunci baris akaun dengan {@code SELECT ... FOR UPDATE} sekaligus
     * me-refresh state entity dari database (versi terbaru), sehingga
     * pengecekan optimistic lock tidak memakai data basi.
     *
     * @param account entity yang sudah managed
     */
    void lockForUpdate(Account account);
}