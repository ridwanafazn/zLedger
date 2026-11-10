package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;
import com.alfagift.finledger.domain.enums.Currency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
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
public interface AccountRepository extends JpaRepository<Account, UUID>, JpaSpecificationExecutor<Account>, AccountRepositoryCustom {

    /**
     * Mencari akaun berdasarkan ID dengan kunci pessimistik tulis.
     * <p>
     * <b>Hanya</b> gunakan kaedah ini di dalam alur yang benar-benar mengubah
     * saldo (checkout, redeem, reversal). Untuk pembacaan biasa gunakan
     * {@code findById} warisan {@link JpaRepository}, agar tidak mengambil
     * kunci tulis pada setiap request baca.
     *
     * @param id ID akaun
     * @return Optional containing the locked account, or empty if not found
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(value = @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

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