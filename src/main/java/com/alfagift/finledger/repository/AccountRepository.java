package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository untuk entiti {@link Account}.
 * <p>
 * Menyediakan kaedah kunci pessimistik untuk mencegah race condition
 * semasa transaksi concurrency tinggi (contoh: flash sale).
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Mencari akaun berdasarkan ID dengan kunci pessimistik tulis.
     * <p>
     * Kaedah ini akan mengunci baris akaun di bilik memori basis data
     * sehingga transaksi selesai, mencegah transaksi lain memodifikasi
     * akaun yang sama secara serentak.
     *
     * @param id ID akaun
     * @return Optional containing the locked account, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(value = @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<Account> findById(UUID id);

    /**
     * Mencari akaun berdasarkan pemilik dan mata wang.
     */
    Optional<Account> findByOwnerIdAndCurrency(String ownerId, com.alfagift.finledger.domain.enums.Currency currency);

    /**
     * Menentukan wujudnya akaun berdasarkan pemilik dan mata wang.
     */
    boolean existsByOwnerIdAndCurrency(String ownerId, com.alfagift.finledger.domain.enums.Currency currency);
}