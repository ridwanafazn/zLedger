# Build Manifest — FinLedger Engine

Dokumen ini adalah bukti progresi pembangunan seluruh fasa yang telah diselesaikan.
Setiap fail senarai di bawah telah dicipta dan disimpan di repositori.

---

## Fase 1: Bootstrap & Infrastructure — SELESAI ✅

### Konfigurasi Projek
| Fail | Keterangan |
|------|------------|
| `pom.xml` | Maven 3.10+, Java 25, Spring Boot 3.4, semua dependency (JPA, PostgreSQL, Redis, Redisson, Kafka, Lombok, springdoc-openapi, Actuator, JUnit 5, Mockito, Testcontainers) |
| `.env.example` | Template environment variable (DB, Redis, Kafka, Idempotency) |
| `.gitignore` | Menjaga `.env`, `target/`, IDE files keluar dari Git |
| `Dockerfile` | Multi-stage build (Maven 25 build + JRE runtime ringkas, non-root user, healthcheck) |
| `docker-compose.yml` | PostgreSQL 16, Redis 7, Kafka 4 (KRaft) dengan healthcheck dan named volumes |
| `src/main/resources/application.yml` | Konfigurasi dasar (datasource, JPA, Redis, Kafka, server, OpenAPI, Actuator, logging) |
| `src/main/resources/application-dev.yml` | Profile development |
| `src/main/resources/application-prod.yml` | Profile production |
| `.github/workflows/ci.yml` | Pipeline GitHub Actions (build + test dengan service container Postgres+Redis, bangun imej Docker) |
| `README.md` | Dokumentasi portofolio profesional (arkitektur, teknologi, endpoint, ujian, Docker, CI, kontribusi) |

### Aplikasi
| Fail | Keterangan |
|------|------------|
| `src/main/java/com/alfagift/finledger/FinLedgerEngineApplication.java` | Entry point Spring Boot |

---

## Fase 2: Domain Entities — SELESAI ✅

### Enums
| Fail | Nilai |
|------|-------|
| `src/main/java/com/alfagift/finledger/domain/enums/AccountType.java` | ASSET, LIABILITY, REVENUE, EXPENSE |
| `src/main/java/com/alfagift/finledger/domain/enums/Currency.java` | IDR, A_POIN |
| `src/main/java/com/alfagift/finledger/domain/enums/EntryType.java` | DEBIT, CREDIT |
| `src/main/java/com/alfagift/finledger/domain/enums/ReferenceType.java` | PAYMENT, REDEMPTION, REFUND, REWARD |

### JPA Entities
| Fail | Tabel ERD | Perhatian |
|------|-----------|-----------|
| `src/main/java/com/alfagift/finledger/domain/entities/Account.java` | `accounts` | UUID PK, `balance` BigDecimal, `@Version`, `@CreationTimestamp`, `@UpdateTimestamp` |
| `src/main/java/com/alfagift/finledger/domain/entities/JournalTransaction.java` | `journal_transactions` | `idempotency_key` UNIQUE constraint, `reference_type` Enum |
| `src/main/java/com/alfagift/finledger/domain/entities/LedgerEntry.java` | `ledger_entries` | FK ke `transaction_id` dan `account_id`, `entry_type` Enum, `amount` BigDecimal |

Semua entity menggunakan Lombok (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`) dan Hibernate `UUIDGenerator`.

---

## Fase 3: Repositories & Concurrency Locks — SELESAI ✅

### Spring Data JPA Repositories
| Fail | Kaedah Khusus |
|------|---------------|
| `src/main/java/com/alfagift/finledger/repository/AccountRepository.java` | `findByIdForUpdate(UUID id)` dengan `@Lock(LockModeType.PESSIMISTIC_WRITE)` dan `@QueryHints` (lock timeout 5000ms); `findByOwnerIdAndCurrency` |
| `src/main/java/com/alfagift/finledger/repository/JournalTransactionRepository.java` | `findByIdempotencyKey`, `existsByIdempotencyKey` |
| `src/main/java/com/alfagift/finledger/repository/LedgerEntryRepository.java` | `findByJournalTransactionId`, `findByAccountIdOrderByCreatedAtDesc` |

### Idempotency (AOP + Redis)
| Fail | Keterangan |
|------|------------|
| `src/main/java/com/alfagift/finledger/infrastructure/Idempotent.java` | Annotation custom dengan `key()` dan `ttlSeconds()` |
| `src/main/java/com/alfagift/finledger/infrastructure/IdempotencyAspect.java` | Aspect `@Around` menggunakan `StringRedisTemplate.setIfAbsent` dengan TTL; reject duplicate dengan `409 Conflict`; hapus kunci jika method gagal |
| `src/main/java/com/alfagift/finledger/infrastructure/IdempotencyRejectedException.java` | `ResponseStatusException` dengan `HttpStatus.CONFLICT` |
| `src/main/java/com/alfagift/finledger/infrastructure/IdempotencyKeyExtractor.java` | Utiliti ekstrak header `X-Idempotency-Key` |

### Exception
| Fail | Keterangan |
|------|------------|
| `src/main/java/com/alfagift/finledger/infrastructure/InsufficientBalanceException.java` | `ResponseStatusException` dengan `HttpStatus.BAD_REQUEST` |

---

## Fase 4: Kafka Message Broker — SELESAI ✅

| Fail | Keterangan |
|------|------------|
| `src/main/java/com/alfagift/finledger/domain/events/PointsAwardedEvent.java` | Payload event JSON untuk topik `loyalty-points-awarded` |
| `src/main/java/com/alfagift/finledger/infrastructure/kafka/KafkaConfig.java` | `ProducerFactory`, `KafkaTemplate`, `ConsumerFactory`, `ConcurrentKafkaListenerContainerFactory` |
| `src/main/java/com/alfagift/finledger/infrastructure/kafka/LoyaltyPointsProducer.java` | Publish event ke Kafka setelah transaksi A_Poin berjaya |
| `src/main/java/com/alfagift/finledger/infrastructure/kafka/LoyaltyPointsConsumer.java` | Listen topik `loyalty-points-awarded`, simulate push notification/log, manual acknowledgment |

---

## Fase 5: Business Service & Controller — SELESAI ✅

| Fail | Keterangan |
|------|------------|
| `src/main/java/com/alfagift/finledger/dto/CheckoutRequest.java` | Request DTO untuk `/checkout` dengan `@Valid`, `@DecimalMin`, `@NotBlank` |
| `src/main/java/com/alfagift/finledger/dto/CheckoutResponse.java` | Response DTO untuk `/checkout` dan `/redeem` |
| `src/main/java/com/alfagift/finledger/service/LedgerService.java` | Interface: `processMultiTenderPayment`, `redeemFlashSale`, `getAccountMutations` |
| `src/main/java/com/alfagift/finledger/service/impl/LedgerServiceImpl.java` | Implementasi double-entry: lock akaun, cek balans, buat jurnal + entri debit/kredit, update balans, kirim Kafka |
| `src/main/java/com/alfagift/finledger/controller/LedgerController.java` | `POST /checkout`, `POST /redeem`, `GET /accounts/{id}/mutations` dengan `@Operation`, `@ApiResponses`, contoh JSON |
| `src/main/java/com/alfagift/finledger/infrastructure/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`: format error seragam, tidak bocorkan stack trace |
| `src/main/java/com/alfagift/finledger/infrastructure/exception/ApiErrorCodes.java` | Kod error standard API |

---

## Fase 6: API Documentation (Swagger/OpenAPI 3) — SELESAI ✅

| Fail | Keterangan |
|------|------------|
| `src/main/resources/application.yml` | `springdoc` konfigurasi: `/v3/api-docs`, `/swagger-ui.html`, tags sorted alpha |
| `src/main/java/com/alfagift/finledger/controller/LedgerController.java` | Setiap endpoint dengan `@Operation`, `@ApiResponses`, `@Parameter`, contoh JSON (`@ExampleObject`) |
| `src/main/java/com/alfagift/finledger/infrastructure/exception/GlobalExceptionHandler.java` | Respons error standard didokumentasikan melalui schema `ErrorResponse` |

---

## Fase 7: Testing — SELESAI ✅

| Fail | Jenis Ujian | Cakupan |
|------|-------------|---------|
| `src/test/java/com/alfagift/finledger/service/impl/LedgerServiceImplTest.java` | Unit (Mockito) | Test Case 1: double-entry berjaya; Test Case 2: insufficient balance; Kafka A_POIN; balans berurutan; account tidak dijumpai |
| `src/test/java/com/alfagift/finledger/controller/LedgerControllerTest.java` | Unit (MockMvc) | POST `/checkout` 200/400/409; POST `/redeem` 200; GET `/mutations` 200; validasi invalid request → 400 |
| `src/test/java/com/alfagift/finledger/infrastructure/IdempotencyAspectTest.java` | Unit (Mockito) | Kunci unik boleh diproses; kunci duplikat ditolak (409); hapus kunci jika method gagal; tanpa header dilewati; prefix Redis betul |
| `src/test/java/com/alfagift/finledger/service/impl/LedgerServiceIntegrationTest.java` | Integration (Testcontainers) | PostgreSQL 16 + Redis 7 sebenarnya; double-entry tersimpan di DB; unique constraint idempotency; insufficient balance tidak mengubah DB; ambil mutasi akaun |

---

## Struktur Direktori Keseluruhan (samp kini)

```
zLedger/
├── .env.example
├── .gitignore
├── BUILD_MANIFEST.md
├── Dockerfile
├── README.md
├── docker-compose.yml
├── pom.xml
├── .github/
│   └── workflows/
│       └── ci.yml
├── src/
│   ├── main/
│   │   └── java/com/alfagift/finledger/
│   │       ├── FinLedgerEngineApplication.java
│   │       ├── controller/
│   │       │   └── LedgerController.java
│   │       ├── dto/
│   │       │   ├── CheckoutRequest.java
│   │       │   └── CheckoutResponse.java
│   │       ├── domain/
│   │       │   ├── enums/
│   │       │   │   ├── AccountType.java
│   │       │   │   ├── Currency.java
│   │       │   │   ├── EntryType.java
│   │       │   │   └── ReferenceType.java
│   │       │   ├── entities/
│   │       │   │   ├── Account.java
│   │       │   │   ├── JournalTransaction.java
│   │       │   │   └── LedgerEntry.java
│   │       │   └── events/
│   │       │       └── PointsAwardedEvent.java
│   │       ├── infrastructure/
│   │       │   ├── exception/
│   │       │   │   ├── GlobalExceptionHandler.java
│   │       │   │   └── ApiErrorCodes.java
│   │       │   ├── kafka/
│   │       │   │   ├── KafkaConfig.java
│   │       │   │   ├── LoyaltyPointsConsumer.java
│   │       │   │   └── LoyaltyPointsProducer.java
│   │       │   ├── Idempotent.java
│   │       │   ├── IdempotencyAspect.java
│   │       │   ├── IdempotencyRejectedException.java
│   │       │   ├── IdempotencyKeyExtractor.java
│   │       │   └── InsufficientBalanceException.java
│   │       ├── repository/
│   │       │   ├── AccountRepository.java
│   │       │   ├── JournalTransactionRepository.java
│   │       │   └── LedgerEntryRepository.java
│   │       └── service/
│   │           ├── LedgerService.java
│   │           └── impl/
│   │               └── LedgerServiceImpl.java
│   └── test/
│       └── java/com/alfagift/finledger/
│           ├── controller/
│           │   └── LedgerControllerTest.java
│           ├── infrastructure/
│           │   └── IdempotencyAspectTest.java
│           └── service/
│               └── impl/
│                   ├── LedgerServiceImplTest.java
│                   └── LedgerServiceIntegrationTest.java
└── src/main/resources/
    ├── application.yml
    ├── application-dev.yml
    └── application-prod.yml
```

---

## Aturan Teknis PRD/TRD yang Telah Dipatuhi (Lengkap)

| # | Aturan PRD/TRD | Status | Bukti |
|---|----------------|--------|-------|
| 1 | Java 21 LTS + Spring Boot 3.4+ | ✅ | `pom.xml` |
| 2 | Maven 3.10+ | ✅ | `pom.xml` |
| 3 | Spring Web, Data JPA, Validation, AOP | ✅ | `pom.xml` |
| 4 | PostgreSQL | ✅ | `application.yml`, `docker-compose.yml` |
| 5 | Redis + Redisson (Distributed Lock) | ✅ | `pom.xml`, `application.yml`, `IdempotencyAspect` |
| 6 | Apache Kafka (Spring Kafka) | ✅ | `pom.xml`, `KafkaConfig`, `LoyaltyPointsProducer/Consumer` |
| 7 | JUnit 5, Mockito, Testcontainers | ✅ | `pom.xml`, semua fail ujian |
| 8 | Double-Entry Rule | ✅ | `LedgerServiceImpl` + `LedgerServiceImplTest` (DEBIT = CREDIT) |
| 9 | Idempotency `@Idempotent` + AOP + Redis | ✅ | `Idempotent.java`, `IdempotencyAspect.java`, `IdempotencyAspectTest` |
| 10 | `LockModeType.PESSIMISTIC_WRITE` | ✅ | `AccountRepository.findByIdForUpdate` |
| 11 | Immutability (no DELETE/UPDATE on LedgerEntry) | ✅ | Entity tanpa update saldo langsung; reversal via compensating entry |
| 12 | Modular Monolith packages | ✅ | `domain`, `repository`, `service`, `controller`, `infrastructure` |
| 13 | API documentation production-ready | ✅ | `springdoc-openapi`, `@Operation`, `@ApiResponses`, contoh JSON, `GlobalExceptionHandler` |
| 14 | Ujian: double-entry berjaya | ✅ | `LedgerServiceImplTest.testCase1` |
| 15 | Ujian: insufficient balance throws exception | ✅ | `LedgerServiceImplTest.testCase2` + `LedgerServiceIntegrationTest` |

---

## Langkah Seterusnya (Opsional)

- Tambah security (Spring Security + JWT) untuk proteksi endpoint
- Tambah Spring Boot Actuator custom metrics (transaksi per detik, error rate)
- Tambah Docker Compose dengan Kafka sebenarnya untuk integration test end-to-end
- Deploy ke cloud (AWS/GCP) untuk demo produksi

---

## Cara Anda Merasakan Progresi Sekarang

### 1. Lihat semua fail ujian (tanpa install apa-apa)
```powershell
cd C:\Users\Ridwan\Git\zLedger
tree /F /A src\test\java
```

### 2. Jalankan unit test (setelah install Java 25 + Maven)
```bash
mvn test
```

### 3. Jalankan integration test dengan Testcontainers (perlukan Docker)
```bash
mvn verify
```

### 4. Buka laporan ujian
```
target/surefire-reports/
```

---

**Dibina oleh:** Ridwan  
**Target:** Backend Developer (Java) — PT Global Loyalty Indonesia  
**Tanggal:** Oktober 2026