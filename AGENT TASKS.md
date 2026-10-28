# Hermes Agent Execution Tasks
**Context:** You are an Expert Principal Java Backend Engineer. You are tasked to build the `FinLedger Engine` using Java 25, Maven, Spring Boot, PostgreSQL, Redis, and Kafka.

**Instructions:** Execute these tasks sequentially. Wait for user confirmation between phases. DO NOT skip any technical requirements from `FinLedger_PRD_TRD.md` and `FinLedger_ERD.md`.

## Phase 1: Bootstrap & Infrastructure Setup
1. Generate the Spring Boot project structure using Maven.
2. Configure `pom.xml` with dependencies: Spring Web, Data JPA, Validation, Redis, Redisson, Kafka, Postgres Driver, Lombok, springdoc-openapi (Swagger), JUnit 5, Mockito.
3. Setup `application.yml` for local PostgreSQL, Redis, and Kafka connections.

## Phase 2: Domain Entities
1. Create Enums (`AccountType`, `Currency`, `EntryType`, `ReferenceType`).
2. Create JPA Entities (`Account`, `JournalTransaction`, `LedgerEntry`) mapping to the ERD.
3. Apply standard JPA annotations, `@Version` for optimistic locking, and Lombok.

## Phase 3: Repositories & Concurrency Locks
1. Create Spring Data Repositories for the entities.
2. In `AccountRepository`, write a method `findByIdForUpdate(UUID id)` with `@Lock(LockModeType.PESSIMISTIC_WRITE)` and `@QueryHints`.
3. Create a custom AOP Annotation `@Idempotent` and its Aspect `IdempotencyAspect` using Redis to reject duplicate `X-Idempotency-Key` headers.

## Phase 4: Message Broker (Kafka)
1. Configure Kafka Producer (`KafkaTemplate`) and Kafka Consumer.
2. Create a consumer that listens to topic `loyalty-points-awarded`. It should simulate sending a push notification/log when a user earns points.

## Phase 5: Business Services (The Ledger Core)
1. Create `LedgerService`. Implement a transactional method `processMultiTenderPayment(...)`.
2. Logic flow:
   - Validate Idempotency (Handled by AOP).
   - Lock required accounts using pessimistic lock.
   - Check if User balance >= required amount. (Throw InsufficientBalanceException if not).
   - Create `JournalTransaction`.
   - Create `LedgerEntry` (Debit User, Credit Merchant).
   - Update `Account` balances.
   - Send Kafka message if A_POIN reward is applicable.

## Phase 6: Controllers & Swagger
1. Create `LedgerController`.
2. Implement POST `/api/v1/ledger/checkout` (Multi-tender).
3. Implement POST `/api/v1/ledger/redeem` (Flash sale simulation).
4. Implement GET `/api/v1/ledger/accounts/{accountId}/mutations`.
5. Annotate all endpoints with Swagger `@Operation`, `@ApiResponses`.

## Phase 7: Testing
1. Write a JUnit 5 test `LedgerServiceTest` using Mockito.
2. Test Case 1: Successful double-entry transaction.
3. Test Case 2: Insufficient balance throws exception.