package com.alfagift.finledger.repository;

import com.alfagift.finledger.domain.entities.Account;

import java.math.BigDecimal;

/**
 * Baris hasil pemeriksaan saldo akun: entity akun + total pergerakan ledger
 * ({@code CREDIT - DEBIT}) yang dihitung ulang dari tabel {@code ledger_entries}.
 */
public record AccountBalanceCheck(Account account, BigDecimal ledgerMovement) {
}
