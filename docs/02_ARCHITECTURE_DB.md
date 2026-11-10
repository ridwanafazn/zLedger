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
- `opening_balance` (DECIMAL 19,4): saldo awal baseline rekonsiliasi; saldo ekspektasi = `opening_balance + CREDIT − DEBIT`.
- `status` (ENUM): ACTIVE, SUSPENDED, CLOSED.
- `version` (BIGINT): JPA Optimistic Lock.

### Table: `journal_transactions`
The header for a business event (e.g., "Checkout #123").
- `id` (UUID, PK)
- `idempotency_key` (VARCHAR 255, UNIQUE): Redis-backed lock prevents duplicates.
- `reference_type` (ENUM): PAYMENT, REDEMPTION, REFUND, REWARD, SETTLEMENT.

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

### Tables: `merchant_settlements` & `settlement_items` (Fase I)
Batch settlement pendapatan merchant; nilai batch adalah **snapshot** dan tidak
berubah setelah dibuat.
- `merchant_settlements`: `merchant_id`, `currency`, periode `[period_start, period_end)`,
  `gross_amount`, `refund_amount`, `fee_percent`, `fee_amount`, `net_amount`,
  status `PENDING|PAID|CANCELLED`, `payout_transaction_id` (jurnal `SETTLEMENT`),
  `idempotency_key` (UNIQUE).
- `settlement_items`: satu baris per jurnal kontribusi (`transaction_id`, `reference_type`,
  `entry_type`, `amount`, `signed_amount`, `occurred_at`), UNIQUE `(settlement_id, transaction_id)`.

Rumus: `gross` = total CREDIT merchant dari jurnal `PAYMENT` dalam periode;
`refund` = total DEBIT merchant dari jurnal `REFUND` (reversal `PAYMENT`);
`fee = (gross − refund) × fee_percent / 100` (skala 4, HALF_UP);
`net = gross − refund − fee`. Payout memposting jurnal `SETTLEMENT`
(DEBIT akun merchant, CREDIT akun clearing `system-settlement`).

### Tables: `reconciliation_runs` & `reconciliation_discrepancies` (Fase I)
Audit trail rekonsiliasi saldo terhadap ledger.
- `reconciliation_runs`: `status` (`BALANCED|DISCREPANCY`), `accounts_checked`,
  `discrepancy_count`, `unbalanced_journal_count`, `total_abs_variance`,
  `triggered_by`, `started_at`, `completed_at`.
- `reconciliation_discrepancies`: `run_id`, `account_id` (nullable), `discrepancy_type`
  (`BALANCE_MISMATCH|UNBALANCED_JOURNAL`), `expected_balance`, `actual_balance`, `variance`, `detail`.

Saldo ekspektasi = `opening_balance + ΣCREDIT − ΣDEBIT`. Selisih dilaporkan sebagai temuan,
bukan diperbaiki otomatis, agar jejak audit tetap utuh.

### Table: `payment_proofs` (Fase J)
Metadata bukti bayar; byte file berada di object storage (MinIO/S3/local), bukan di PostgreSQL.
- `transaction_id` (FK `journal_transactions`), `uploader_id`
- `object_key` (UNIQUE), `original_filename`, `content_type`, `size_bytes`
- `checksum_sha256` (UNIQUE per transaksi) — mencegah unggahan file yang sama dua kali
- `created_at`

Alur: validasi tipe + magic bytes → hitung SHA-256 → simpan blob → simpan metadata.
Bila metadata gagal (mis. duplikat), blob dihapus kembali. Detail keputusan:
[`BLOB_STORAGE_DECISION.md`](BLOB_STORAGE_DECISION.md).

## 3. Observability (Fase K)

- **Metrik:** Micrometer → `/actuator/prometheus` (registry Prometheus).
  - HTTP: `http_server_requests_seconds{...}` (histogram, p50/p95/p99).
  - Domain: `finledger.checkout` (timer), `finledger.settlement.created|paid|cancelled|rejected`,
    `finledger.settlement.payout.amount`, `finledger.reconciliation.runs|discrepancies|duration`,
    `finledger.payment_proof.uploaded|rejected|size.bytes`.
  - Runtime: JVM, HikariCP, Tomcat.
  - Tag global `application=finledger-engine`.
- **Scrape:** Prometheus (`observability/prometheus.yml`, port 9090) menarik
  `host.docker.internal:8080/actuator/prometheus` setiap 15 detik.
- **Visualisasi:** Grafana (port 3000) dengan datasource & dashboard yang di-provision otomatis
  (`observability/grafana/`).
- `/actuator/prometheus` dibuka untuk scrape; di produksi batasi lewat network policy
  atau `management.server.port` terpisah.