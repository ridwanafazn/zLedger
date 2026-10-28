# FinLedger Entity Relationship Diagram (ERD)

This document defines the database schema for the Double-Entry Accounting system.
Agent must translate this into JPA Entities with proper constraints.

## Constraints & Rules
- `currency` uses Enums (IDR, A_POIN).
- `account_type` uses Enums (ASSET, LIABILITY, REVENUE, EXPENSE).
- All amounts must use `BigDecimal` in Java for precision.

## Schema Details

### 1. `accounts` (Wallet / Ledger Account)
Represents a user's wallet, merchant's revenue pool, or system promo pool.
- `id` (UUID, PK)
- `owner_id` (String) - Represents User ID or System ID
- `account_name` (String) - e.g., "Ridwan A-Poin Wallet"
- `account_type` (Enum) 
- `currency` (Enum) 
- `balance` (BigDecimal) - Current cached balance.
- `version` (Long) - For JPA Optimistic Locking (fallback).

### 2. `journal_transactions` (Transaction Header)
Records the event that triggered the ledger movement.
- `id` (UUID, PK)
- `idempotency_key` (String, UNIQUE, Indexed) - Header from client.
- `reference_type` (Enum) - e.g., PAYMENT, REDEMPTION, REFUND, REWARD.
- `description` (String)
- `created_at` (Timestamp)

### 3. `ledger_entries` (Transaction Lines)
The double-entry lines. One transaction must have at least 2 entries (1 Debit, 1 Credit).
- `id` (UUID, PK)
- `transaction_id` (UUID, FK to journal_transactions)
- `account_id` (UUID, FK to accounts)
- `entry_type` (Enum) - DEBIT / CREDIT
- `amount` (BigDecimal)
- `created_at` (Timestamp)

## Accounting Logic
- To deduct from User (Liability Account): DEBIT the user account.
- To add to Merchant (Revenue Account): CREDIT the merchant account.
- Total DEBIT amount must equal Total CREDIT amount per `transaction_id`.