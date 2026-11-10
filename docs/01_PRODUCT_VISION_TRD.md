# 01. Product Vision & Technical Requirements (PRD & TRD)

## 1. Product Vision: The "FinLedger Engine"
**FinLedger Engine** is a production-grade, double-entry ledger backend designed to simulate the core transactional engine of a massive retail ecosystem (like Alfagift). 
It focuses on achieving zero data-inconsistency during high-concurrency scenarios (e.g., Flash Sales, Multi-Tender Checkouts) while maintaining a strict, append-only financial audit trail.

## 2. The "Mobile-First" Backend Promise
This API is designed to make iOS and Android developers smile:
- **Predictable Error Contracts:** Adheres strictly to RFC 7807 (Problem Details for HTTP APIs). No more guessing error structures.
- **Idempotency Out-of-the-Box:** Mobile apps can safely retry requests during network drops without fear of double-charging users.
- **Lightning Fast:** Sub-50ms response times guaranteed through Redis caching and asynchronous Kafka processing.

## 3. Tech Stack & Infrastructure (Aligning with Enterprise Needs)
- **Core:** Java 21 LTS, Spring Boot 3.4.0, Maven.
- **Database:** PostgreSQL 16 (Relational/ACID strictness) + Hibernate 6.6.
- **Concurrency Control:** Redisson (Distributed Redis Locks) & JPA Pessimistic Locking.
- **Event Streaming (Message Broker):** Apache Kafka 3.8 (Spring Kafka).
- **Documentation:** OpenAPI 3.0 (Springdoc v2.6.0+).
- **Testing:** JUnit 5, Mockito, Testcontainers (Postgres, Redis, Kafka).
- **Deployment:** Docker, CI/CD (GitHub Actions), targeted for Google Cloud Run (GCP) or AWS ECS.

## 4. Key Business Use Cases (The "Alfagift" Scenarios)
1. **Multi-Tender Checkout:** Combining IDR (Fiat) and A-Poin (Loyalty Points) in a single atomic transaction.
2. **High-Concurrency Flash Sale:** Safely redeeming limited vouchers using A-Poin without race conditions or overselling.
3. **Async Point Earning:** Awarding cashback points via Kafka without blocking the main checkout thread.
4. **Immutable Audit Trail:** Reversals/Refunds only. No `UPDATE` or `DELETE` on ledger balances.