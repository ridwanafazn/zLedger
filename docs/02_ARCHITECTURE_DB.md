# 02. System Architecture & Database Design

## 1. Architectural Pattern: Modular Monolith
The project strictly follows Clean Architecture principles:
`Controller (Delivery)` -> `Service (Use Cases)` -> `Repository (Data Access)` -> `Domain (Entities)`.
Modules are logically separated (e.g., `account`, `ledger`, `transaction`), paving an easy migration path to Microservices if scaling demands it.

## 2. Double-Entry Database Schema (PostgreSQL)

### Table: `ledger_accounts`
Stores the balances. Locked pessimistically during transactions.
- `id` (UUID, PK)
- `owner_id` (VARCHAR): Belongs to User, Merchant, or System.
- `account_type` (ENUM): ASSET, LIABILITY, REVENUE, EXPENSE.
- `currency` (ENUM): IDR, A_POINT, VOUCHER.
- `balance` (DECIMAL 19,4)
- `status` (ENUM): ACTIVE, SUSPENDED, CLOSED.
- `version` (BIGINT): JPA Optimistic Lock.

### Table: `journal_transactions`
The header for a business event (e.g., "Checkout #123").
- `id` (UUID, PK)
- `idempotency_key` (VARCHAR 64, UNIQUE): Redis-backed lock prevents duplicates.
- `reference_type` (ENUM): CHECKOUT, REFUND, POINT_EARN.
- `status` (ENUM): PENDING, COMPLETED, FAILED.

### Table: `ledger_entries`
The immutable movements (Debits/Credits). 
*Rule: Total DEBIT must exactly equal Total CREDIT per transaction.*
- `id` (UUID, PK)
- `transaction_id` (UUID, FK)
- `account_id` (UUID, FK)
- `entry_type` (ENUM): DEBIT, CREDIT.
- `amount` (DECIMAL 19,4)