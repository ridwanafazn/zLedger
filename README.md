# FinLedger Engine

> **High-concurrency, double-entry ledger backend** simulating Alfagift's A-Poin and Multi-Tender payment system.
> Built as a portfolio-grade backend engineering project for **PT Global Loyalty Indonesia (GLI)** — Yogyakarta.

[![Java 21](https://img.shields.io/badge/Java-21%20LTS-007396?logo=java)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=spring-boot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Kafka-4.x-231F2D?logo=apache-kafka)](https://kafka.apache.org/)
[![Maven](https://img.shields.io/badge/Maven-3.10+-C71A36?logo=apache-maven)](https://maven.apache.org/)
[![CI](https://img.shields.io/github/actions/workflow/status/ridwanafazn/zLedger/ci.yml?label=CI)](https://github.com/ridwanafazn/zLedger/actions/workflows/ci.yml)
[![Security Scan](https://img.shields.io/github/actions/workflow/status/ridwanafazn/zLedger/security-scan.yml?label=security)](https://github.com/ridwanafazn/zLedger/actions/workflows/security-scan.yml)

---

## Tentang Projek

FinLedger Engine adalah backend ledger **double-entry** yang dirancang untuk memenuhi skenario finansial nyata:

1. **Multi-Tender Payment** — Pelanggan membayar item dengan campuran balans IDR dan A_Poin.
2. **Flash Sale Redemption** — Tebasan voucher terbatas dengan A_Poin di bawah tekanan concurrency tinggi.
3. **Earn Points (Asynchronous)** — A_Poin diberikan selepas transaksi melalui Apache Kafka tanpa memblokir aliran checkout utama.
4. **Ledger Audit** — Sejarah imutabiliti semua pergerakan balans/poin (tidak ada DELETE/UPDATE pada entri ledger).

Projek ini dibangun sebagai **Modular Monolith** dengan **Clean Architecture**, mematuhi semua prinsip **SOLID**, dan siap untuk dokumentasi API produksi melalui **Springdoc OpenAPI 3 (Swagger UI)**.

---

## Arsitektur & Struktur Paket

```
com.alfagift.finledger
├── domain          # Entities & Enums (Account, JournalTransaction, LedgerEntry, ...)
├── repository      # Spring Data JPA repositories
├── service         # Business logic interfaces + implementations
├── controller      # REST APIs (dokumentasikan via OpenAPI 3)
└── infrastructure  # Kafka config, Redis config, AOP aspects (@Idempotent), Exceptions
```

### Aturan Teknikal Ketat (dari PRD/TRD)

| # | Aturan | Pelaksanaan |
|---|--------|-------------|
| 1 | **Double-Entry** | Jumlah DEBIT = Jumlah CREDIT bagi setiap `JournalTransaction` |
| 2 | **Idempotency** | `@Idempotent` (AOP + Redis) menolak duplicate `X-Idempotency-Key` |
| 3 | **Concurrency** | `LockModeType.PESSIMISTIC_WRITE` pada akaun dalam transaksi |
| 4 | **Immutability** | Tiada DELETE/UPDATE pada `LedgerEntry`; reversal via compensating entry |

---

## Teknologi

| Layer | Teknologi | Versi |
|-------|-----------|-------|
| Bahasa | Java | 21 LTS |
| Framework | Spring Boot | 3.4 |
| Database | PostgreSQL | 16 |
| Cache / Lock | Redis + Redisson | 7 / 3.33 |
| Message Broker | Apache Kafka | 4.x (KRaft) |
| API Docs | Springdoc OpenAPI | 2.7.0 |
| Migrasi DB | Flyway | 10.17.1 |
| Build | Maven | 3.10+ |
| Ujian | JUnit 5, Mockito, Testcontainers | 1.21.x |

---

## Memulakan Projek (Local Development)

### Prasyarat

Pastikan berikut dipasang di sistem anda:

```bash
java --version      # Java 21 LTS
mvn --version       # Maven 3.10+
docker --version    # Docker Desktop
docker compose version
```

### 1. Clone repositori

```bash
git clone https://github.com/ridwan/finledger-engine.git
cd finledger-engine
```

### 2. Siapkan environment

```bash
cp .env.example .env
# Sunting .env jika perlu (default boleh digunakan untuk development)
```

### 3. Jalankan infrastruktur (PostgreSQL, Redis, Kafka)

```bash
docker compose up -d
```

Semak kesihatan kontena:

```bash
docker compose ps
```

### 4. Build dan jalankan aplikasi

```bash
mvn clean install
mvn spring-boot:run
```

Aplikasi akan berjalan di `http://localhost:8080`.

### 5. Akses dokumentasi API (Swagger UI)

Buka pelayar dan kunjungi:

```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON tersedia di:

```
http://localhost:8080/v3/api-docs
```

---

## Autentikasi & Otorisasi

Semua endpoint (kecuali registrasi, login, refresh, dan dokumentasi) memerlukan JWT.

```bash
# 0) Registrasi pengguna baru (role otomatis CUSTOMER)
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"budi","password":"rahasia123","email":"budi@example.com"}'

# 1) Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 2) Gunakan token
curl http://localhost:8080/api/v1/ledger/accounts \
  -H "Authorization: Bearer <accessToken>"
```

> Sejak Fase H, user disimpan di tabel `app_users` (BCrypt). Tiga akun demo
> di-seed sekali saat startup bila belum ada, dan **tidak** menimpa user yang
> sudah tersimpan. Password seed dapat ditimpa lewat environment
> (`ADMIN_PASSWORD`, dst) dan `JWT_SECRET`.

| Role | Akun demo | Izin |
|------|-----------|------|
| `ADMIN` | `admin` / `admin123` | Semua endpoint, termasuk kelola user (`/api/v1/users/**`) |
| `MERCHANT` | `merchant` / `merchant123` | Checkout & redeem |
| `CUSTOMER` | `customer` / `customer123` | Checkout & redeem, baca data |

Mekanisme keamanan tambahan:

- Password di-hash **BCrypt**, tidak pernah dikembalikan API.
- Refresh token **opaque + rotasi sekali pakai** di Redis.
- Menonaktifkan user (`PATCH /users/{id}/status`) mencabut **semua** refresh token
  dan menandai access token aktif sebagai dicabut (Redis), sehingga akses berhenti
  seketika — bukan menunggu JWT kedaluwarsa.
- Registrasi publik tidak dapat memilih role (cegah privilege escalation).

> Di Swagger UI, klik **Authorize** dan tempel token untuk mencoba endpoint terproteksi.
> Error 401/403 juga berformat RFC 7807.

## Endpoint API (Ringkasan)

| Method | Endpoint | Deskripsi | Status |
|--------|----------|-----------|--------|
| `POST` | `/api/v1/ledger/checkout` | Pembayaran multi-tender (IDR + A_Poin) | ✅ |
| `POST` | `/api/v1/ledger/redeem` | Tebasan flash sale (A_Poin) | ✅ |
| `GET` | `/api/v1/ledger/accounts/{accountId}/mutations` | Sejarah mutasi akaun | ✅ |
| `POST` | `/api/v1/ledger/accounts` | Buat akun ledger | ✅ |
| `GET` | `/api/v1/ledger/accounts/{accountId}` | Detail akun | ✅ |
| `GET` | `/api/v1/ledger/accounts/{accountId}/balance` | Inquiry saldo | ✅ |
| `PATCH` | `/api/v1/ledger/accounts/{accountId}/status` | Ubah status (ACTIVE/SUSPENDED/CLOSED) | ✅ |
| `GET` | `/api/v1/ledger/accounts` | Cari akun (filter + pagination) | ✅ |
| `GET` | `/api/v1/ledger/transactions` | Daftar transaksi (filter `referenceType`, `accountId`, `from`, `to`) | ✅ |
| `GET` | `/api/v1/ledger/transactions/{transactionId}` | Detail transaksi + entri debit/credit | ✅ |
| `POST` | `/api/v1/ledger/transactions/{transactionId}/reverse` | Reversal via jurnal kompensasi (REFUND) | ✅ |
| `POST` | `/api/v1/auth/register` | Registrasi user baru (CUSTOMER) | ✅ |
| `POST` | `/api/v1/auth/login` | Login → access + refresh token | ✅ |
| `POST` | `/api/v1/auth/refresh` | Rotasi refresh token | ✅ |
| `POST` | `/api/v1/auth/logout` | Cabut refresh token | ✅ |
| `GET` | `/api/v1/auth/me` | Profil user dari database | ✅ |
| `POST` | `/api/v1/users` | Buat user dengan role apa pun (ADMIN) | ✅ |
| `GET` | `/api/v1/users` | Cari user (role/status/teks + pagination, ADMIN) | ✅ |
| `GET` | `/api/v1/users/{userId}` | Detail user (ADMIN) | ✅ |
| `PATCH` | `/api/v1/users/{userId}/status` | Aktifkan/nonaktifkan user (ADMIN) | ✅ |
| `GET` | `/api/v1/ledger/accounts/{accountId}/mutations` | Mutasi akun (paginated) | ✅ |
| `POST` | `/api/v1/vouchers` | Buat voucher (ADMIN) | ✅ |
| `GET` | `/api/v1/vouchers` | Katalog voucher (filter + pagination) | ✅ |
| `GET` | `/api/v1/vouchers/{voucherId}` | Detail voucher | ✅ |
| `PATCH` | `/api/v1/vouchers/{voucherId}/status` | Aktifkan/nonaktifkan voucher (ADMIN) | ✅ |
| `POST` | `/api/v1/vouchers/{voucherId}/redeem` | Redeem voucher kuota terbatas | ✅ |

**Kontrak lintas-endpoint:**
- `POST`/`PATCH` wajib `X-Idempotency-Key`. Retry identik mengembalikan respons sukses yang sama (cached), payload berbeda → `409`.
- Semua error berformat **RFC 7807** (`application/problem+json`) dengan `type`, `title`, `detail`, `instance`, `errorCode`.
- Setiap respons menyertakan `X-Request-ID` (dipakai juga di log).
- Metrik checkout tersedia di `/actuator/metrics/ledger.checkout`.
- CORS diaktifkan untuk `http://localhost:3000` dan `http://localhost:5173` (atur via `CORS_ALLOWED_ORIGINS`).
- Reversal tidak mengubah transaksi asal; dibuat jurnal `REFUND` dengan entri kompensasi (`debit ↔ credit`).

---

## Dokumentasi Desain

| Dokumen | Isi |
|---------|-----|
| [`docs/01_PRODUCT_VISION_TRD.md`](docs/01_PRODUCT_VISION_TRD.md) | Visi produk & technical requirements |
| [`docs/02_ARCHITECTURE_DB.md`](docs/02_ARCHITECTURE_DB.md) | Arsitektur modular monolith & desain database |
| [`docs/03_API_CONTRACTS.md`](docs/03_API_CONTRACTS.md) | Kontrak API, idempotency, RFC 7807 |
| [`docs/04_AGENT_TASKS.md`](docs/04_AGENT_TASKS.md) | Roadmap eksekusi & status sprint |
| [`docs/05_LOAD_TEST_PROOFS.md`](docs/05_LOAD_TEST_PROOFS.md) | Rencana bukti konkurensi & performa |
| [`docs/ADR/`](docs/ADR/) | Architecture Decision Records |
| [`PRD TRD.md`](PRD%20TRD.md) | Product & Technical Requirements (historis) |
| [`ERD.md`](ERD.md) | Entity Relationship Diagram |
| [`BUILD_MANIFEST.md`](BUILD_MANIFEST.md) | Bukti progres & roadmap |

---

## Ujian

```bash
# Unit test (Mockito, MockMvc) — tidak butuh Docker
mvn test

# Integration test dengan Testcontainers (PostgreSQL) — butuh Docker
mvn verify
```

> Unit test dijalankan oleh Surefire; integration test (`*IntegrationTest`) oleh Failsafe
> pada fase `verify`, sehingga `mvn test` tetap hijau tanpa Docker.

### Load test & static analysis

```bash
# Harness konkurensi lokal (butuh aplikasi + docker compose aktif)
powershell -ExecutionPolicy Bypass -File loadtest/local-loadtest.ps1 -Parallel 50

# Skrip k6 (jika tersedia)
k6 run -e VUS=50 -e ITERATIONS=200 loadtest/k6/flash-sale.js

# Laporan SpotBugs (dihasilkan saat mvn verify)
mvn -DskipTests verify
# → target/spotbugsXml.xml

# OWASP Dependency-Check (advisory, unduh database NVD)
mvn -Psecurity-scan -DskipTests verify
```

Hasil pengukuran nyata (flash sale race, retry storm, outbox) ada di
[`docs/05_LOAD_TEST_PROOFS.md`](docs/05_LOAD_TEST_PROOFS.md).

Laporan ujian akan ditimpan di `target/surefire-reports/`.

---

## Docker (Production)

### Bangun imej

```bash
docker build -t finledger-engine:1.0.0 .
```

### Jalankan

```bash
docker run -p 8080:8080 --env-file .env finledger-engine:1.0.0
```

### Docker Compose (infrastruktur sahaja)

```bash
docker compose up -d
```

---

## CI/CD

Setiap push dan pull request ke `main` / `develop` akan memicu pipeline GitHub Actions:

1. Build Maven (`mvn clean verify`)
2. Ujian dengan PostgreSQL dan Redis sebagai service container
3. Publish artefact ujian
4. Bangun imej Docker (hanya push ke `main`)

Lihat fail: `.github/workflows/ci.yml`

---

## Kontribusi

Projek ini adalah portofolio pribadi untuk permohonan kerja Backend Developer di GLI. Feedback, cadangan rekabentuk, dan pull request dibuka untuk meningkatkan kualiti kod dan dokumentasi.

1. Fork repositori
2. Buat branch fitur (`git checkout -b fitur/nama-fitur`)
3. Commit mengikut conventional commits (`feat:`, `fix:`, `docs:`, ...)
4. Buka pull request

---

## Lisensi

Projek ini dibina untuk tujuan portofolio karir. Tidak terdapat klien komersial yang terlibat.

---

**Dibina oleh:** Ridwan  
**Target:** Backend Developer (Java) — PT Global Loyalty Indonesia  
**Lokasi:** Yogyakarta  
**Tanggal:** Oktober 2026