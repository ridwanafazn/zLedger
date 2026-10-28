# FinLedger Engine

> **High-concurrency, double-entry ledger backend** simulating Alfagift's A-Poin and Multi-Tender payment system.
> Built as a portfolio-grade backend engineering project for **PT Global Loyalty Indonesia (GLI)** — Yogyakarta.

[![Java 25](https://img.shields.io/badge/Java-25%20LTS-007396?logo=java)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?logo=spring-boot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Kafka-4.x-231F2D?logo=apache-kafka)](https://kafka.apache.org/)
[![Maven](https://img.shields.io/badge/Maven-3.10+-C71A36?logo=apache-maven)](https://maven.apache.org/)
[![CI](https://img.shields.io/github/actions/workflow/status/ridwan/finledger-engine/ci.yml?label=CI)](https://github.com/ridwan/finledger-engine/actions)

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
| Bahasa | Java | 25 LTS |
| Framework | Spring Boot | 3.4 |
| Database | PostgreSQL | 16 |
| Cache / Lock | Redis + Redisson | 7 / 3.33 |
| Message Broker | Apache Kafka | 4.x (KRaft) |
| API Docs | Springdoc OpenAPI | 2.6.0 |
| Build | Maven | 3.10+ |
| Ujian | JUnit 5, Mockito, Testcontainers | Latest |

---

## Memulakan Projek (Local Development)

### Prasyarat

Pastikan berikut dipasang di sistem anda:

```bash
java --version      # Java 25 LTS
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

## Endpoint API (Ringkasan)

| Method | Endpoint | Deskripsi |
|--------|----------|-----------|
| `POST` | `/api/v1/ledger/checkout` | Pembayaran multi-tender (IDR + A_Poin) |
| `POST` | `/api/v1/ledger/redeem` | Tebasan flash sale (A_Poin) |
| `GET` | `/api/v1/ledger/accounts/{accountId}/mutations` | Sejarah mutasi akaun |

Semua endpoint dilengkapi `@Operation`, `@ApiResponses`, dan contoh payload dalam Swagger UI.

---

## Ujian

```bash
# Unit test (Mockito)
mvn test

# Integration test dengan Testcontainers (PostgreSQL + Redis)
mvn verify
```

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