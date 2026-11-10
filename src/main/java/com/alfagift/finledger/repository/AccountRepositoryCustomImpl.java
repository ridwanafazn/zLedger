package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.Currency;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Custom repository implementation for Account entity.
 * Uses EntityManager directly to handle enum casting properly in PostgreSQL.
 */
@Repository
@Transactional(readOnly = true)
public class AccountRepositoryCustomImpl implements AccountRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Account> findAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency) {
        // String interpolation for enum value to avoid Hibernate parameter parser issues with ::currency cast
        // Safe because currency is a controlled enum (IDR/A_POIN only)
        String sql = "SELECT * FROM accounts WHERE owner_id = ? AND currency = '" + currency.name() + "'::currency";
        Query query = entityManager.createNativeQuery(sql, Account.class);
        query.setParameter(1, ownerId);
        @SuppressWarnings("unchecked")
        java.util.List<Account> results = query.getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public boolean existsAccountByOwnerIdAndCurrencyCustom(String ownerId, Currency currency) {
        String sql = "SELECT EXISTS(SELECT 1 FROM accounts WHERE owner_id = ? AND currency = '" + currency.name() + "'::currency)";
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter(1, ownerId);
        return (Boolean) query.getSingleResult();
    }

    @Override
    public void lockForUpdate(Account account) {
        UUID id = account.getId();
        // Lepaskan instance lama, lalu muat ulang dengan pessimistic write lock
        // supaya state dan versi benar-benar terbaru (menghindari optimistic failure).
        entityManager.detach(account);
        Account fresh = entityManager.find(Account.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (fresh == null) {
            throw new jakarta.persistence.EntityNotFoundException("Akun tidak ditemukan: " + id);
        }
        // Salin state terbaru kembali ke instance milik pemanggil.
        account.setOwnerId(fresh.getOwnerId());
        account.setAccountName(fresh.getAccountName());
        account.setAccountType(fresh.getAccountType());
        account.setCurrency(fresh.getCurrency());
        account.setBalance(fresh.getBalance());
        account.setOpeningBalance(fresh.getOpeningBalance());
        account.setStatus(fresh.getStatus());
        account.setVersion(fresh.getVersion());
        account.setCreatedAt(fresh.getCreatedAt());
        account.setUpdatedAt(fresh.getUpdatedAt());
    }
}