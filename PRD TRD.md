# Product & Technical Requirements Document (PRD & TRD)
**Project Name:** FinLedger Engine (Enterprise Loyalty & Multi-Tender Ledger)
**Target Context:** PT Global Loyalty Indonesia (Alfagift) - Backend Engineer Portfolio

## 1. Product Overview
FinLedger Engine is a high-concurrency, double-entry ledger backend designed to simulate Alfagift's A-Poin and Multi-Tender payment system. It ensures 100% data integrity, zero double-deductions during network timeouts (Idempotency), and safe concurrent transactions (Flash Sales).

## 2. Business Use Cases
1. **Multi-Tender Payment:** User buys an item using a mix of IDR Balance and A_POIN.
2. **Flash Sale Redemption:** High-concurrency point redemption where users race to claim limited vouchers using A_POIN.
3. **Earn Points (Asynchronous):** System awards A_POIN to users post-transaction via Message Broker without blocking the main checkout thread.
4. **Ledger Audit:** Immutable history of all point/balance movements.

## 3. Technical Requirements (TRD)
- **Language:** Java 25 LTS
- **Build Tool:** Maven 3.10+
- **Framework:** Spring Boot 3.4+ (Spring Web, Spring Data JPA, Spring AOP)
- **Database:** PostgreSQL (Relational constraints, ACID)
- **Caching & Locking:** Redis via Redisson (Distributed Lock), Spring Data Redis
- **Message Broker:** Apache Kafka (Spring Kafka)
- **Testing:** JUnit 5, Mockito, Testcontainers

## 4. Architecture Standards (Modular Monolith)
The project must strictly follow Clean Architecture principles within a Monolith layout. 
**Allowed Packages:**
- `com.alfagift.finledger.domain` (Entities, Enums)
- `com.alfagift.finledger.repository` (Spring Data JPA)
- `com.alfagift.finledger.service` (Business Logic, Interfaces)
- `com.alfagift.finledger.controller` (REST APIs)
- `com.alfagift.finledger.infrastructure` (Kafka config, Redis config, AOP aspects, Exceptions)

## 5. System Best Practices (Strict Rules for Agent)
1. **Double-Entry Rule:** `LedgerEntry` sum of DEBIT must exactly equal CREDIT for a single transaction.
2. **Idempotency:** Implement `@Idempotent` custom annotation using Spring AOP + Redis. Reject duplicate requests containing the same `X-Idempotency-Key` header.
3. **Concurrency:** Use `LockModeType.PESSIMISTIC_WRITE` on Wallet/Account queries during transactions to prevent Race Conditions.
4. **Immutability:** No `DELETE` or `UPDATE` queries on Ledger Entries. Reversals must be done by inserting compensating entries.