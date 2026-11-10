# 02. System Architecture & Database Design

## 1. Architectural Pattern: Modular Monolith
The project strictly follows Clean Architecture principles:
`Controller (Delivery)` -> `Service (Use Cases)` -> `Repository (Data Access)` -> `Domain (Entities)`.
Modules are logically separated (e.g., `account`, `ledger`, `transaction`), paving an easy migration path to Microservices if scaling demands it.

## 2. Double-Entry Database Schema (PostgreSQL)

### Table: `accounts`
Stores the balances. Locked pessimistically during transactions.
- `id` (UUID, PK)
- `owner_id` (VARCHAR): Belongs to User, Merchant, or System.
- `account_name` (VARCHAR): Human-readable label.
- `account_type` (ENUM): ASSET, LIABILITY, REVENUE, EXPENSE.
- `currency` (ENUM): IDR, A_POIN.
- `balance` (DECIMAL 19,4)
- `status` (ENUM): ACTIVE, SUSPENDED, CLOSED.
- `version` (BIGINT): JPA Optimistic Lock.

### Table: `journal_transactions`
The header for a business event (e.g., "Checkout #123").
- `id` (UUID, PK)
- `idempotency_key` (VARCHAR 255, UNIQUE): Redis-backed lock prevents duplicates.
- `reference_type` (ENUM): PAYMENT, REDEMPTION, REFUND, REWARD.

### Table: `ledger_entries`
The immutable movements (Debits/Credits). 
*Rule: Total DEBIT must exactly equal Total CREDIT per transaction.*
- `id` (UUID, PK)
- `transaction_id` (UUID, FK)
- `account_id` (UUID, FK)
- `entry_type` (ENUM): DEBIT, CREDIT.
- `amount` (DECIMAL 19,4)

### Table: `app_users`
User store for authentication (migration `V6`).
- `id` (UUID, PK)
- `username` (VARCHAR 64, UNIQUE, lowercase)
- `password_hash` (VARCHAR 100): BCrypt, never returned by the API.
- `email` (VARCHAR 255, UNIQUE, nullable)
- `full_name` (VARCHAR 128, nullable)
- `enabled` (BOOLEAN): disabling revokes refresh tokens and active access tokens (Redis).
- `version` (BIGINT): JPA optimistic lock.

### Table: `app_user_roles`
RBAC roles per user (`ADMIN`, `MERCHANT`, `CUSTOMER`), composite PK `(user_id, role)`.
Demo users are seeded once from `app.security.users` only when they do not exist yet.

### Tables: `vouchers` & `voucher_redemptions`
Voucher catalog with limited quota. Quota is decremented under `PESSIMISTIC_WRITE`
so concurrent redeems cannot oversell; `(voucher_id, owner_id)` is unique so a
user can redeem the same voucher only once.
- `vouchers`: `code`, `points_cost`, `total_quota`, `remaining_quota`, period, status.
- `voucher_redemptions`: link to voucher, owner, ledger `transaction_id`, points spent.