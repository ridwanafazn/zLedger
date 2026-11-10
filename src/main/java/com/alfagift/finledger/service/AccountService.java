package com.alfagift.finledger.service;

import com.alfagift.finledger.dto.AccountCreateRequest;
import com.alfagift.finledger.dto.AccountResponse;
import com.alfagift.finledger.dto.AccountStatusRequest;
import com.alfagift.finledger.dto.BalanceResponse;
import com.alfagift.finledger.dto.PageResponse;
import com.alfagift.finledger.infrastructure.InsufficientBalanceException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Service untuk operasi manajemen akun.
 */
public interface AccountService {

    /**
     * Membuat akun baru.
     *
     * @param request detail akun
     * @param idempotencyKey kunci idempotency dari header
     * @return akun yang dibuat
     */
    AccountResponse createAccount(AccountCreateRequest request, String idempotencyKey);

    /**
     * Mengambil detail akun by ID.
     */
    AccountResponse getAccount(UUID accountId);

    /**
     * Mengambil saldo akun (lightweight).
     */
    BalanceResponse getBalance(UUID accountId);

    /**
     * Mengubah status akun.
     *
     * @param accountId ID akun
     * @param request status baru + alasan
     * @param idempotencyKey kunci idempotency
     * @return akun dengan status terupdate
     */
    AccountResponse updateStatus(UUID accountId, AccountStatusRequest request, String idempotencyKey);

    /**
     * Mencari akun dengan filter dan pagination.
     */
    PageResponse<AccountResponse> searchAccounts(
            String ownerId,
            String currency,
            String accountType,
            String status,
            int page,
            int size,
            String sort);

    /**
     * Validasi dan cek saldo cukup untuk transaksi.
     * Melempar InsufficientBalanceException jika tidak cukup.
     */
    void validateSufficientBalance(UUID accountId, BigDecimal requiredAmount);
}