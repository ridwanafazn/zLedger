# FinLedger Engine — API Specification (v1)

> **Status:** FINAL (design lock) · **Version:** API v1 · **Last updated:** 2026-10-29
>
> Dokumen ini adalah **single source of truth** untuk kontrak API antara Backend dan Frontend.
> Semua endpoint, DTO, validasi, error code, edge case, dan perilaku async didefinisikan di sini
> agar frontend dummy dapat dibangun **tanpa menebak-nebak** dan **tanpa bug**.

---

## 0. Konvensi Global

### 0.1 Base Path & Versioning
- Semua endpoint di bawah `/api/v1/ledger`.
- Versioning via URL path (`/v1/`). Breaking change → `/v2/`, versi lama diberi header `Sunset`.

### 0.2 Media Type
- Request/Response: `application/json` (UTF-8).
- Error: `application/problem+json` (RFC 7807 Problem Details).

### 0.3 Format Data
| Tipe | Format | Contoh |
|------|--------|--------|
| UUID | RFC 4122 lowercase | `550e8400-e29b-41d4-a716-446655440000` |
| Timestamp | ISO-8601 UTC, milidetik | `2026-10-29T10:30:00.123Z` |
| Date | ISO-8601 | `2026-10-29` |
| Amount | JSON number, precision 2, non-negatif | `150000.00` |
| Currency | Enum | `IDR` \| `A_POIN` |
| Boolean | JSON boolean | `true` / `false` |

> **Catatan FE:** `amount` dan `balance` selalu number (bukan string). FE wajib memformat tampilan (ribuan/desimal) sendiri. Semua uang **tanpa** simbol mata uang di response.

### 0.4 Pagination Standar
Request query: `page`, `size`, `sort`.
- `page` default `0`, min `0`.
- `size` default `20`, min `1`, **max `100`** (lebih → `400 PAGE_SIZE_EXCEEDED`).
- `sort` format `field,direction` (mis. `createdAt,desc`), boleh multiple.

Response wrapper (`PageResponse<T>`) **selalu sama**:
```json
{
  "content": [ ... ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 145,
    "totalPages": 8,
    "first": true,
    "last": false,
    "numberOfElements": 20
  },
  "sort": [{ "field": "createdAt", "direction": "DESC" }],
  "filters": { "ownerId": "cust-001" }
}
```

### 0.5 Error Format (RFC 7807)
Semua error memakai struktur berikut:
```json
{
  "type": "https://finledger.example.com/errors/insufficient-balance",
  "title": "Insufficient Balance",
  "status": 400,
  "errorCode": "INSUFFICIENT_BALANCE",
  "detail": "Saldo IDR tidak mencukupi: tersedia 50000, butuh 100000",
  "instance": "/api/v1/ledger/checkout",
  "correlationId": "req-7f3a9c2e",
  "timestamp": "2026-10-29T10:30:00.123Z",
  "errors": [
    { "field": "tenders[0].amount", "message": "Melebihi saldo tersedia" }
  ]
}
```
- `errors[]` **hanya muncul** untuk kegagalan validasi (field-level). Kosong/absen untuk error bisnis non-field.

### 0.6 Idempotency Standar
Semua endpoint **write** (`POST`/`PATCH`/`DELETE`) yang memutasi state wajib idempotent.

Request header:
| Header | Required | Deskripsi |
|--------|----------|-----------|
| `X-Idempotency-Key` | Ya | UUID v4, digenerate client |

Response header:
| Header | Deskripsi |
|--------|-----------|
| `X-Idempotency-Key` | Echo key request |
| `Idempotency-Status` | `processed` \| `pending` \| `failed` |
| `Idempotency-TTL-Seconds` | `86400` |

Aturan:
- Key sama + request sama → kembalikan **response identik** (tidak reprocess).
- Key sama + body berbeda → `409 IDEMPOTENCY_CONFLICT` dengan pesan "Key sudah dipakai untuk request berbeda".
- Key tidak ada → `400 VALIDATION_ERROR` (field `X-Idempotency-Key`).

### 0.7 Correlation & Server Time
Setiap response menyertakan:
| Header | Deskripsi |
|--------|-----------|
| `X-Request-ID` | Correlation ID (diteruskan ke log & Kafka header) |
| `X-Server-Time` | Waktu server UTC (untuk sinkronisasi countdown FE) |

### 0.8 Rate Limiting
Response menyertakan (bila limit aktif):
| Header | Deskripsi |
|--------|-----------|
| `X-RateLimit-Limit` | Kuota per window |
| `X-RateLimit-Remaining` | Sisa kuota |
| `X-RateLimit-Reset` | Epoch detik reset |
| `Retry-After` | Detik (hanya saat `429`) |

### 0.9 Autentikasi (preview — diimplementasi di fase Security)
- `Authorization: Bearer <JWT>`.
- Role/scope per endpoint dicantumkan di tiap bagian.
- Ownership check: customer hanya boleh akses resource miliknya (`ownerId` = subjek token).

---

## 1. Katalog Kode Error

| HTTP | errorCode | Kapan |
|------|-----------|-------|
| 400 | `VALIDATION_ERROR` | Bean validation gagal (field-level) |
| 400 | `AMOUNT_MISMATCH` | `sum(tenders) != totalAmount` |
| 400 | `DUPLICATE_TENDER_CURRENCY` | Currency duplikat dalam tenders |
| 400 | `INSUFFICIENT_BALANCE` | Saldo IDR kurang |
| 400 | `INSUFFICIENT_POINTS` | Saldo A_POIN kurang |
| 400 | `INVALID_AMOUNT` | Amount <= 0 atau melebihi batas |
| 400 | `INVALID_STATE_TRANSITION` | Perubahan status tidak diizinkan |
| 400 | `MERCHANT_INACTIVE` | Merchant tidak ACTIVE |
| 400 | `VOUCHER_EXPIRED` | Voucher di luar masa berlaku |
| 400 | `VOUCHER_NOT_ACTIVE` | Voucher belum ACTIVE / PAUSED |
| 400 | `VOUCHER_QUOTA_EXHAUSTED` | Kuota voucher habis |
| 400 | `VOUCHER_LIMIT_REACHED` | Melebihi batas per customer |
| 400 | `PAGE_SIZE_EXCEEDED` | `size` > 100 |
| 400 | `INVALID_SORT_FIELD` | Field sort tidak diizinkan |
| 401 | `UNAUTHENTICATED` | Token absen/invalid |
| 403 | `FORBIDDEN` | Role/scope tidak cukup |
| 404 | `ACCOUNT_NOT_FOUND` | Akun tidak ditemukan |
| 404 | `MERCHANT_NOT_FOUND` | Merchant tidak ditemukan |
| 404 | `TRANSACTION_NOT_FOUND` | Transaksi tidak ditemukan |
| 404 | `VOUCHER_NOT_FOUND` | Voucher tidak ditemukan |
| 404 | `REWARD_NOT_FOUND` | Reward tidak ditemukan |
| 409 | `IDEMPOTENCY_CONFLICT` | Key duplikat beda body |
| 409 | `CONCURRENCY_CONFLICT` | Optimistic lock gagal |
| 409 | `ACCOUNT_ALREADY_EXISTS` | Akun owner+currency sudah ada |
| 409 | `ALREADY_REVERSED` | Transaksi sudah pernah di-reverse |
| 422 | `UNPROCESSABLE_ENTITY` | Semantik valid tapi tidak dapat diproses |
| 429 | `RATE_LIMIT_EXCEEDED` | Melebihi rate limit |
| 500 | `INTERNAL_ERROR` | Kesalahan tak terduga |
| 503 | `SERVICE_UNAVAILABLE` | Dependensi (DB/Kafka/Redis) down |

---

## 2. State Machines

### 2.1 Account Status
```
ACTIVE ──suspend──> SUSPENDED ──activate──> ACTIVE
  │                     │
  │close                │close
  v                     v
CLOSED <────────────────┘
  │
  └──reopen──> ACTIVE        (admin only)
```
| Status | Boleh checkout/redeem | Boleh query |
|--------|-----------------------|-------------|
| `ACTIVE` | ✅ | ✅ |
| `SUSPENDED` | ❌ (`400 INVALID_STATE_TRANSITION`) | ✅ |
| `CLOSED` | ❌ | ✅ (read-only) |

Allowed transitions:
- `ACTIVE → SUSPENDED` ✅
- `SUSPENDED → ACTIVE` ✅
- `ACTIVE/SUSPENDED → CLOSED` ✅
- `CLOSED → ACTIVE` ✅ (admin/reopen)
- `CLOSED → SUSPENDED` ❌ → `400 INVALID_STATE_TRANSITION`

### 2.2 Transaction Status
```
SUCCESS ──reverse──> REVERSED
   │
   └──(gagal saat proses, tidak tersimpan)──> FAILED (transient, tidak persist)
```
- Hanya `SUCCESS` dapat di-reverse. `REVERSED` final (immutable).
- `FAILED` tidak pernah dipersist (rollback penuh), muncul hanya di reward async.

### 2.3 Voucher Status
```
DRAFT ──activate──> ACTIVE ──pause──> PAUSED ──activate──> ACTIVE
                       │                  │
                       │expire            │expire
                       v                  v
                    EXPIRED <─────────── EXPIRED
```
Allowed transitions:
- `DRAFT → ACTIVE` ✅
- `ACTIVE → PAUSED` ✅
- `PAUSED → ACTIVE` ✅
- `ACTIVE/PAUSED → EXPIRED` ✅
- `EXPIRED → *` ❌ (final)
- `DRAFT → PAUSED` ❌

### 2.4 Reward Grant Status (async)
```
PENDING ──> PROCESSING ──> COMPLETED
                  │
                  └──> FAILED ──retry──> PROCESSING
```

---

## 3. User Personas & Endpoint Access

| Persona | Deskripsi | Endpoint utama |
|---------|-----------|----------------|
| **Customer** | Pengguna Alfagift | checkout, redeem, balance, mutations (own) |
| **Merchant/Partner** | Penerima pembayaran | voucher CRUD, settlement report, logo upload |
| **Ops/Finance** | Tim internal | transaction search, reconciliation, reversal |
| **Admin** | System admin | semua + status changes, idempotency admin |
| **Async Worker** | Sistem | consume Kafka `loyalty-points-awarded` |

---

## 4. Endpoint Reference

> Format tiap endpoint: Overview · Auth (role/scope) · Idempotency · Request · Response · Error · Edge Cases · FE Notes.

---

### 4.1 `POST /checkout` — Multi-Tender Payment

**Overview:** Membayar item dengan campuran saldo IDR dan A_POIN. Double-entry ledger, idempotent.

**Auth:** `CUSTOMER` (ownerId harus = subjek token).

**Idempotency:** Wajib. TTL 24 jam.

**Request Headers:**
| Header | Required |
|--------|----------|
| `Authorization` | Ya |
| `X-Idempotency-Key` | Ya |
| `Content-Type` | Ya (`application/json`) |

**Request Body (`CheckoutRequest`):**
| Field | Type | Required | Constraints | Deskripsi |
|-------|------|----------|-------------|-----------|
| `ownerId` | string | Ya | `@NotBlank`, max 64 | ID pelanggan |
| `merchantId` | string | Ya | `@NotBlank`, max 64 | ID merchant |
| `currency` | enum | Ya | `IDR`\|`A_POIN` | Mata uang settlement |
| `totalAmount` | number | Ya | `@DecimalMin("0.01")`, `@Digits(15,2)` | Total pembayaran |
| `idempotencyKey` | string | Ya | `@UUID` | Duplikasi key di body juga diterima |
| `tenders` | array | Ya | `@NotEmpty`, `@Size(max=10)`, `@Valid` | Daftar tender |

**`TenderDetail`:**
| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `currency` | enum | Ya | `IDR`\|`A_POIN` |
| `amount` | number | Ya | `@DecimalMin("0.01")`, `@Digits(15,2)` |
| `description` | string | Ya | `@NotBlank`, max 255 |

**Cross-field Validation:**
- `sum(tenders[].amount) == totalAmount` → else `400 AMOUNT_MISMATCH`.
- `tenders[].currency` unik → else `400 DUPLICATE_TENDER_CURRENCY`.

**Success Response (200) — `CheckoutResponse`:**
| Field | Type | Nullable | Deskripsi |
|-------|------|----------|-----------|
| `transactionId` | uuid | No | ID jurnal |
| `ownerId` | string | No | |
| `merchantId` | string | No | |
| `currency` | enum | No | |
| `totalAmount` | number | No | |
| `balanceRemaining` | number | No | Saldo **currency utama** setelah transaksi |
| `processedAt` | datetime | No | ISO-8601 UTC |
| `status` | enum | No | `SUCCESS` |
| `ledgerEntries` | array | No | Rincian DEBIT/CREDIT |

**Error Scenarios:**
| HTTP | errorCode | Detail | Field |
|------|-----------|--------|-------|
| 400 | `VALIDATION_ERROR` | "Validasi gagal" | per-field |
| 400 | `AMOUNT_MISMATCH` | "Total tender (X) != totalAmount (Y)" | `tenders` |
| 400 | `DUPLICATE_TENDER_CURRENCY` | "Currency duplikat: IDR" | `tenders` |
| 400 | `INSUFFICIENT_BALANCE` | "Saldo IDR tidak mencukupi: tersedia X, butuh Y" | `tenders[i].amount` |
| 400 | `INSUFFICIENT_POINTS` | "Poin tidak mencukupi: tersedia X, butuh Y" | `tenders[i].amount` |
| 400 | `INVALID_AMOUNT` | "Total amount harus > 0" | `totalAmount` |
| 400 | `INVALID_STATE_TRANSITION` | "Akun berstatus SUSPENDED/CLOSED" | `ownerId` |
| 400 | `MERCHANT_INACTIVE` | "Merchant {id} tidak aktif" | `merchantId` |
| 401 | `UNAUTHENTICATED` | "Token tidak valid" | — |
| 403 | `FORBIDDEN` | "ownerId bukan milik token" | `ownerId` |
| 404 | `ACCOUNT_NOT_FOUND` | "Akun {ownerId}/{currency} tidak ditemukan" | `ownerId` |
| 404 | `MERCHANT_NOT_FOUND` | "Merchant {id} tidak ditemukan" | `merchantId` |
| 409 | `IDEMPOTENCY_CONFLICT` | "Key sudah dipakai request berbeda" | — |
| 409 | `CONCURRENCY_CONFLICT` | "Data berubah, coba lagi" | — |
| 429 | `RATE_LIMIT_EXCEEDED` | "Terlalu banyak request" | — |
| 500 | `INTERNAL_ERROR` | "Kesalahan internal" | — |
| 503 | `SERVICE_UNAVAILABLE` | "Layanan sementara tidak tersedia" | — |

**Edge Cases:**
1. **Partial tender gagal** → seluruh transaksi rollback (atomik). Tidak ada ledger parsial.
2. **Network retry** → key sama mengembalikan response identik (tidak dobel potong).
3. **Concurrent checkout akun sama** → pessimistic lock; satu menang, lainnya `409 CONCURRENCY_CONFLICT`.
4. **Kafka publish gagal** → transactional outbox menjamin eventual publish (tidak memblok checkout).
5. **Merchant suspended mid-transaction** → dicek di dalam transaksi → `MERCHANT_INACTIVE`.
6. **Amount presisi > 2** → `VALIDATION_ERROR` (tidak dibulatkan diam-diam).
7. **tenders kosong** → `VALIDATION_ERROR`.

**FE Notes:**
- Tampilkan `balanceRemaining` segera setelah sukses (tanpa refetch).
- Gunakan `X-Server-Time` untuk sinkron countdown.
- Disable tombol submit saat in-flight; simpan `X-Idempotency-Key` untuk retry aman.

---

### 4.2 `POST /redeem` — Flash Sale Redemption

**Overview:** Menebus voucher flash sale dengan A_POIN. High-concurrency, guarded by Redis lock + quota check.

**Auth:** `CUSTOMER`.

**Idempotency:** Wajib.

**Request:** Query params (untuk kompatibilitas saat ini) + body opsional. **Deprecation plan:** pindah ke body JSON di v1.1 — lihat `docs/ADR/003-redeem-request-shape.md`.
| Param | In | Type | Required | Deskripsi |
|-------|----|------|----------|-----------|
| `ownerId` | query | string | Ya | ID pelanggan |
| `voucherId` | query | uuid | Ya | ID voucher |
| `points` | query | integer | Ya | Poin yang ditebus (>=1) |
| `idempotencyKey` | query | string | Ya | UUID v4 |

**Success Response (200) — `CheckoutResponse`** (sama seperti checkout).

**Error Scenarios:**
| HTTP | errorCode | Detail |
|------|-----------|--------|
| 400 | `INSUFFICIENT_POINTS` | "Poin tidak mencukupi: tersedia X, butuh Y" |
| 400 | `VOUCHER_EXPIRED` | "Voucher {code} sudah kadaluarsa" |
| 400 | `VOUCHER_NOT_ACTIVE` | "Voucher belum aktif / dijeda" |
| 400 | `VOUCHER_QUOTA_EXHAUSTED` | "Kuota voucher habis" |
| 400 | `VOUCHER_LIMIT_REACHED` | "Melebihi batas tebus per pelanggan" |
| 400 | `INVALID_AMOUNT` | "points harus >= 1" |
| 404 | `VOUCHER_NOT_FOUND` | "Voucher {id} tidak ditemukan" |
| 404 | `ACCOUNT_NOT_FOUND` | "Akun A_POIN {ownerId} tidak ditemukan" |
| 409 | `IDEMPOTENCY_CONFLICT` | — |
| 409 | `CONCURRENCY_CONFLICT` | "Stok diperebutkan, coba lagi" |

**Edge Cases:**
1. **Race kuota (N user klaim bersamaan)** → Redis atomic decrement + DB constraint; yang kalah `VOUCHER_QUOTA_EXHAUSTED` atau `CONCURRENCY_CONFLICT`.
2. **Voucher habis di tengah** → tidak ada poin terpotong (rollback).
3. **Double-click user** → idempotency mencegah dobel tebus.
4. **Voucher mulai/diakhiri tepat di detik request** → bandingkan dengan `X-Server-Time`, bukan waktu client.
5. **points != voucher.pointsCost** → `VALIDATION_ERROR` (harus sama).

**FE Notes:**
- Tampilkan **sisa kuota** via polling `GET /vouchers/{id}` (interval 3–5s) selama flash sale; lihat 4.9.
- Countdown pakai `validTo - X-Server-Time`.
- Disable tombol saat kuota 0 atau `PAUSED/EXPIRED`.

---

### 4.3 `POST /accounts` — Create Account

**Overview:** Provinsi akun baru (customer/merchant/system).

**Auth:** `ADMIN` (self-service registration di luar scope).

**Idempotency:** Wajib.

**Request Body (`AccountCreateRequest`):**
| Field | Type | Required | Constraints | Deskripsi |
|-------|------|----------|-------------|-----------|
| `ownerId` | string | Ya | `@NotBlank`, max 64 | User/System ID |
| `accountName` | string | Ya | `@NotBlank`, max 128 | Nama tampilan |
| `accountType` | enum | Ya | `ASSET`\|`LIABILITY`\|`REVENUE`\|`EXPENSE` | Tipe akun |
| `currency` | enum | Ya | `IDR`\|`A_POIN` | Mata uang |
| `initialBalance` | number | Tidak | `@DecimalMin("0")`, default `0` | Saldo awal |

**Success Response (201) — `AccountResponse`:**
| Field | Type | Deskripsi |
|-------|------|-----------|
| `id` | uuid | |
| `ownerId` | string | |
| `accountName` | string | |
| `accountType` | enum | |
| `currency` | enum | |
| `balance` | number | |
| `status` | enum | `ACTIVE` (default) |
| `version` | integer | Optimistic lock |
| `createdAt` | datetime | |
| `updatedAt` | datetime | |

**Error Scenarios:**
| HTTP | errorCode | Detail | Field |
|------|-----------|--------|-------|
| 400 | `VALIDATION_ERROR` | — | per-field |
| 400 | `INVALID_AMOUNT` | "initialBalance harus >= 0" | `initialBalance` |
| 403 | `FORBIDDEN` | butuh ADMIN | — |
| 409 | `ACCOUNT_ALREADY_EXISTS` | "Akun {ownerId}/{currency} sudah ada" | `ownerId` |

**Edge Cases:**
1. **Duplikat ownerId + currency** → `409 ACCOUNT_ALREADY_EXISTS` (unique constraint).
2. **initialBalance > 0** untuk akun LIABILITY → diperbolehkan (seed saldo), tercatat sebagai entri jurnal `REWARD`/`ADJUSTMENT` (auditable).
3. **accountType/currency invalid enum** → `VALIDATION_ERROR`.

**FE Notes:**
- Form harus pilih `accountType` & `currency` (dropdown dari enum).
- Setelah create, redirect ke `GET /accounts/{id}`.

---

### 4.4 `GET /accounts` — List Accounts

**Auth:** `ADMIN` / `FINANCE`. Customer hanya melihat miliknya (filter `ownerId` dipaksa).

**Query Params:** `ownerId`, `currency`, `accountType`, `status`, `page`, `size`, `sort`.
- Sortable fields: `createdAt`, `updatedAt`, `balance`, `accountName`.

**Success Response (200):** `PageResponse<AccountResponse>`.

**Error:** `400 PAGE_SIZE_EXCEEDED`, `400 INVALID_SORT_FIELD`.

**Edge Cases:**
1. Filter tidak cocok → `content: []`, bukan 404.
2. Sort field ilegal → `400 INVALID_SORT_FIELD` (whitelist).
3. `page` melebihi total → `content: []`.

**FE Notes:**
- Gunakan metadata `page.*` untuk pagination control.
- Debounce filter input (300ms) sebelum request.

---

### 4.5 `GET /accounts/{accountId}` — Account Detail

**Auth:** owner atau `ADMIN`/`FINANCE`.

**Response (200):** `AccountResponse` (tanpa `ledgerEntries` untuk menghindari payload besar).

**Error:** `404 ACCOUNT_NOT_FOUND`, `403 FORBIDDEN`.

**FE Notes:** Gunakan endpoint ini untuk header profil dompet.

---

### 4.6 `GET /accounts/{accountId}/balance` — Balance Inquiry (Lightweight)

**Auth:** owner atau `ADMIN`/`FINANCE`.

**Response (200) — `BalanceResponse`:**
| Field | Type | Deskripsi |
|-------|------|-----------|
| `accountId` | uuid | |
| `ownerId` | string | |
| `currency` | enum | |
| `balance` | number | Saldo saat ini |
| `asOf` | datetime | Waktu snapshot |
| `status` | enum | |

**Error:** `404 ACCOUNT_NOT_FOUND`.

**Edge Cases:** Endpoint ini **read-only**, tidak pakai lock (eventual consistency untuk listing), tapi `asOf` memberi tahu kejelasan waktu.

**FE Notes:** Cocok untuk polling ringan saldo tanpa beban payload penuh.

---

### 4.7 `PATCH /accounts/{accountId}/status` — Change Account Status

**Auth:** `ADMIN`.

**Idempotency:** Wajib.

**Request Body (`AccountStatusRequest`):**
| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `status` | enum | Ya | `ACTIVE`\|`SUSPENDED`\|`CLOSED` |
| `reason` | string | Tidak | max 255 |

**Response (200):** `AccountResponse`.

**Error:**
| HTTP | errorCode | Detail |
|------|-----------|--------|
| 400 | `INVALID_STATE_TRANSITION` | "Transisi {from}→{to} tidak diizinkan" |
| 404 | `ACCOUNT_NOT_FOUND` | — |

**Edge Cases:**
1. Transisi ilegal (mis. `CLOSED → SUSPENDED`) → `400 INVALID_STATE_TRANSITION`.
2. Set status sama dengan saat ini → idempotent sukses (no-op), tetap 200.
3. Akun dengan saldo ≠ 0 di-`CLOSED` → diperbolehkan, saldo tetap tercatat.

**FE Notes:** Tampilkan hanya transisi legal (ambil dari state machine) — disable tombol ilegal.

---

### 4.8 `GET /accounts/{accountId}/mutations` — Ledger Entry History

**Auth:** owner atau `ADMIN`/`FINANCE`.

**Query Params:** `from`, `to`, `entryType` (`DEBIT`\|`CREDIT`), `page`, `size`, `sort`.
- Sortable: `createdAt`, `amount`.

**Response (200):** `PageResponse<LedgerEntryResponse>`:
| Field | Type | Deskripsi |
|-------|------|-----------|
| `id` | uuid | |
| `transactionId` | uuid | |
| `accountId` | uuid | |
| `entryType` | enum | `DEBIT`\|`CREDIT` |
| `amount` | number | |
| `currency` | enum | |
| `description` | string | |
| `referenceType` | enum | `PAYMENT`\|`REDEMPTION`\|`REFUND`\|`REWARD` |
| `createdAt` | datetime | |

**Error:** `404 ACCOUNT_NOT_FOUND`, `400 INVALID_SORT_FIELD`.

**Edge Cases:**
1. Akun tanpa mutasi → `content: []`.
2. Range tanggal invalid (`from > to`) → `400 VALIDATION_ERROR`.
3. **Immutability:** tidak ada endpoint untuk UPDATE/DELETE entry — koreksi via reversal.

**FE Notes:** Group by tanggal untuk tampilan statement; sediakan tombol "load more" pakai metadata page.

---

### 4.9 `GET /transactions` — Search Transactions

**Auth:** `ADMIN`/`FINANCE`; customer dibatasi ke miliknya.

**Query Params:** `referenceType`, `from`, `to`, `accountId`, `idempotencyKey`, `minAmount`, `maxAmount`, `page`, `size`, `sort`.
- Sortable: `createdAt`, `totalAmount`.

**Response (200):** `PageResponse<TransactionSummaryResponse>`:
| Field | Type | Deskripsi |
|-------|------|-----------|
| `id` | uuid | |
| `referenceType` | enum | |
| `description` | string | |
| `totalAmount` | number | |
| `currency` | enum | |
| `status` | enum | `SUCCESS`\|`REVERSED` |
| `createdAt` | datetime | |

**Error:** `400 INVALID_SORT_FIELD`, `400 VALIDATION_ERROR` (range/amount negatif).

**Edge Cases:**
1. `minAmount > maxAmount` → `400 VALIDATION_ERROR`.
2. Hasil besar → pagination wajib; endpoint tidak streaming.

**FE Notes:** Tabel + filter panel; kolom status pakai badge warna.

---

### 4.10 `GET /transactions/{transactionId}` — Transaction Detail

**Auth:** owner/`ADMIN`/`FINANCE`.

**Response (200) — `TransactionDetailResponse`:**
| Field | Type | Deskripsi |
|-------|------|-----------|
| `id` | uuid | |
| `referenceType` | enum | |
| `description` | string | |
| `idempotencyKey` | string | |
| `status` | enum | `SUCCESS`\|`REVERSED` |
| `createdAt` | datetime | |
| `entries` | array | `LedgerEntryResponse[]` (min 2: DEBIT=CREDIT) |
| `reversedBy` | uuid \| null | ID jurnal reversal bila ada |

**Error:** `404 TRANSACTION_NOT_FOUND`, `403 FORBIDDEN`.

**Edge Cases:**
1. Transaksi yang sudah di-reverse → `status: REVERSED`, `reversedBy` terisi.
2. Invariant: `sum(DEBIT) == sum(CREDIT)` — jika tidak, server mengembalikan `500 INTERNAL_ERROR` (data corrupt, alert).

**FE Notes:** Tampilkan tabel debit/kredit bersisian dengan total yang seimbang.

---

### 4.11 `POST /transactions/{transactionId}/reverse` — Reversal (Compensating Entry)

**Overview:** Membalik transaksi SUCCESS dengan membuat jurnal kompensasi (bukan update/delete). Immutability terjaga.

**Auth:** `LEDGER_ADMIN`.

**Idempotency:** Wajib.

**Request Body (`ReversalRequest`):**
| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `reason` | string | Ya | `@NotBlank`, max 255 |

**Response (201) — `TransactionDetailResponse`:** transaksi reversal baru (DEBIT↔CREDIT ditukar).

**Error:**
| HTTP | errorCode | Detail |
|------|-----------|--------|
| 404 | `TRANSACTION_NOT_FOUND` | — |
| 409 | `ALREADY_REVERSED` | "Transaksi sudah pernah di-reverse" |
| 400 | `INVALID_STATE_TRANSITION` | "Hanya transaksi SUCCESS dapat di-reverse" |
| 403 | `FORBIDDEN` | butuh LEDGER_ADMIN |

**Edge Cases:**
1. Reverse dua kali → `409 ALREADY_REVERSED` (idempotent-guarded).
2. Reverse transaksi `REVERSED` → ditolak.
3. Reverse transaksi REDEMPTION → kuota voucher **tidak otomatis dikembalikan** (keputusan bisnis: manual) — didokumentasikan.
4. Reverse harus menjaga `sum(DEBIT)==sum(CREDIT)` pada jurnal kompensasi.

**FE Notes:** Konfirmasi modal wajib (aksi irreversible); tampilkan `reason`.

---

### 4.12 Voucher Management

#### 4.12.1 `POST /vouchers` — Create Voucher
**Auth:** `PARTNER_ADMIN`/`ADMIN`. **Idempotency:** Wajib.
Body (`VoucherCreateRequest`):
| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `voucherCode` | string | Ya | `@Pattern("[A-Z0-9-]{4,32}")` unik |
| `partnerId` | string | Ya | `@NotBlank`, harus ada |
| `pointsCost` | integer | Ya | `@Min(1)` |
| `totalQuantity` | integer | Ya | `@Min(1)` |
| `maxPerCustomer` | integer | Tidak | `@Min(1)`, default 1 |
| `validFrom` | datetime | Ya | < `validTo` |
| `validTo` | datetime | Ya | > now |
| `description` | string | Tidak | max 255 |
| `imageKey` | string | Tidak | dari blob storage (lihat BLOB_STORAGE_DECISION) |

Response (201) — `VoucherResponse` (lihat 4.12.2).

Errors: `409 VOUCHER_CODE_EXISTS`, `404 MERCHANT_NOT_FOUND`, `400 VALIDATION_ERROR`.

Edge cases: `validFrom >= validTo` → 400; partner tidak ada → 404.

#### 4.12.2 `GET /vouchers` / `GET /vouchers/{voucherId}` — List/Detail
**Auth:** `PARTNER_ADMIN`/`ADMIN` (list); publik untuk detail aktif (untuk FE flash sale).
`VoucherResponse`:
| Field | Type | Deskripsi |
|-------|------|-----------|
| `id` | uuid | |
| `voucherCode` | string | |
| `partnerId` | string | |
| `pointsCost` | integer | |
| `totalQuantity` | integer | |
| `remainingQuantity` | integer | **untuk FE stok live** |
| `maxPerCustomer` | integer | |
| `status` | enum | `DRAFT`\|`ACTIVE`\|`PAUSED`\|`EXPIRED` |
| `validFrom` / `validTo` | datetime | |
| `description` | string | |
| `imageUrl` | string \| null | presigned URL |

Errors: `404 VOUCHER_NOT_FOUND`.

#### 4.12.3 `PATCH /vouchers/{voucherId}/status` — Change Status
**Auth:** `PARTNER_ADMIN`/`ADMIN`. **Idempotency:** Wajib.
Body: `{ "status": "ACTIVE"|"PAUSED"|"EXPIRED" }`.
Errors: `400 INVALID_STATE_TRANSITION`, `404 VOUCHER_NOT_FOUND`.

#### 4.12.4 `GET /vouchers/{voucherId}/redemptions` — Redemption History
**Auth:** `PARTNER_ADMIN`/`ADMIN`.
Response: `PageResponse<RedemptionResponse>` (`transactionId`, `ownerId`, `points`, `redeemedAt`).
Sortable: `redeemedAt`.

---

### 4.13 Rewards (Async)

#### 4.13.1 `POST /rewards` — Grant Reward
**Overview:** Memberi A_POIN via Kafka (async). Mengembalikan `202 Accepted`.
**Auth:** `REWARD_WRITE`/`ADMIN`. **Idempotency:** Wajib.
Body (`RewardGrantRequest`): `ownerId`, `points (@Min(1))`, `referenceType`, `description`.
Response (202) + header `Location: /rewards/{rewardId}`:
```json
{ "rewardId": "...", "status": "PENDING", "estimatedCompletionAt": "..." }
```

#### 4.13.2 `GET /rewards/{rewardId}` — Reward Status
**Auth:** owner/`ADMIN`.
Response: `{ rewardId, ownerId, points, status, createdAt, completedAt }`.
Status: `PENDING`\|`PROCESSING`\|`COMPLETED`\|`FAILED`.
**FE Notes:** Polling dengan backoff (`Retry-After` header), terminal state `COMPLETED`/`FAILED`.

---

### 4.14 Settlement & Reconciliation

#### 4.14.1 `GET /settlements/merchant/{merchantId}` — Settlement Report
**Auth:** `PARTNER_ADMIN`/`FINANCE`.
Query: `from`, `to` (wajib), `page`, `size`.
Response: `PageResponse<SettlementLineResponse>` + summary (`netAmount`, `fees`, `count`).

#### 4.14.2 `GET /reconciliation/daily` — Daily Reconciliation
**Auth:** `FINANCE`. Query: `date` (default hari ini).
Response: `{ date, totalDebit, totalCredit, balanced: true|false, accountSummary: [...] }`.

#### 4.14.3 `POST /reconciliation/trigger` — Manual Trigger
**Auth:** `FINANCE`/`ADMIN`. **Idempotency:** Wajib. Response `202`.

---

### 4.15 Idempotency Admin

#### 4.15.1 `GET /idempotency/{key}` — Check Status
**Auth:** `ADMIN`. Response: `{ key, status, resourceId, createdAt }`.

#### 4.15.2 `DELETE /idempotency/{key}` — Force Release
**Auth:** `ADMIN`. **Idempotency:** Wajib. Response `204`.
Edge case: key tidak ada → `204` (idempotent no-op).

---

### 4.16 Blob Storage / Upload (lihat `docs/BLOB_STORAGE_DECISION.md`)

#### 4.16.1 `POST /upload/presigned` — Get Presigned Upload URL
**Auth:** sesuai konteks (`PARTNER_ADMIN` untuk voucher, dst). **Idempotency:** tidak (read-ish).
Body: `{ fileName, contentType, folder: "vouchers"|"merchants"|"receipts" }`.
Response: `{ uploadUrl, fileKey, publicUrl|null, expiresInSeconds }`.
Constraints: `contentType` whitelist (`image/png`, `image/jpeg`, `image/webp`, `application/pdf`); `maxSizeBytes` 10MB.

#### 4.16.2 `POST /upload/callback` — Confirm Upload
**Auth:** sesuai konteks. Body: `{ fileKey, entityType, entityId }`.
Response: `{ fileId, fileKey, publicUrl }`. Verifikasi objek ada di storage sebelum simpan metadata.

#### 4.16.3 `GET /files/{fileId}` — Presigned Download
**Auth:** owner/`ADMIN`. Response: `{ downloadUrl, expiresInSeconds }`.

#### 4.16.4 `DELETE /files/{fileId}` — Delete File
**Auth:** owner/`ADMIN`. Response `204`.

**Edge Cases (blob):**
1. FE upload langsung ke MinIO → BE tidak pernah sentuh byte file (scalable).
2. Upload gagal / tidak panggil callback → file orphan; dicek oleh scheduled reaper.
3. Content type dipalsukan → validasi magic bytes di callback.
4. Key path traversal (`../`) → sanitasi `fileKey`.

**FE Flow:** lihat BLOB_STORAGE_DECISION §5.

---

## 5. FE Comfort Checklist (dipenuhi kontrak ini)

| Kebutuhan FE | Disediakan |
|--------------|-----------|
| Tahu field wajib & constraint | ✅ Tabel constraint per field |
| Tahu semua error code & pesan | ✅ §1 + per-endpoint errors |
| Bedakan validation vs business error | ✅ `errorCode` + `errors[]` |
| Pagination konsisten | ✅ `PageResponse<T>` seragam |
| Sorting terwhitelist | ✅ `x-sortable-fields` per endpoint |
| Idempotent retry aman | ✅ header idempotency standar |
| Retry async aman | ✅ `Retry-After` + polling backoff |
| Countdown akurat | ✅ `X-Server-Time` |
| Stok voucher live | ✅ `remainingQuantity` + polling 4.12.2 |
| Upload tanpa beban BE | ✅ presigned URL |
| State transition jelas | ✅ §2 state machines |
| Saldo instan pasca bayar | ✅ `balanceRemaining` di response |

---

## 6. Roadmap Implementasi Endpoint (referensi, bukan komitmen tanggal)

| Prioritas | Endpoint | Status |
|-----------|----------|--------|
| P0 | `POST /checkout` | ✅ Implemented |
| P0 | `POST /redeem` | ✅ Implemented |
| P0 | `GET /accounts/{id}/mutations` | ✅ Implemented |
| P0 | `POST/GET /accounts`, `/balance`, `PATCH /status` | ⏳ Planned |
| P0 | `GET /transactions`, `/transactions/{id}` | ⏳ Planned |
| P1 | `POST /transactions/{id}/reverse` | ⏳ Planned |
| P1 | Voucher CRUD (4.12) | ⏳ Planned |
| P1 | Blob upload (4.16) | ⏳ Planned |
| P2 | Rewards (4.13) | ⏳ Planned |
| P2 | Settlement/Reconciliation (4.14) | ⏳ Planned |
| P3 | Idempotency admin (4.15) | ⏳ Planned |

---

## 7. Perubahan Dokumen
| Versi | Tanggal | Perubahan |
|-------|---------|-----------|
| 1.0 | 2026-10-29 | Design lock: katalog endpoint, state machine, error code, edge case, FE checklist |
