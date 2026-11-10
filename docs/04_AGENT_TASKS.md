# 04. Agent Execution Roadmap & Tasks

**Dear Autonomous Agent (Hermes/DeepSeek),**
You are a Principal Java Engineer. Read the current environment state carefully before modifying code.

## ✅ COMPLETED (Do NOT touch or recreate)
- [x] Phase 1: Spring Boot 3.4.0 & Java 21 Scaffolding.
- [x] Phase 2: PostgreSQL, Redis, Kafka via Docker Compose.
- [x] Phase 3: Core Ledger Domain Entities & Flyway (V1 & V2).
- [x] Phase 4: `Account` & `Ledger` Services & Controllers.
- [x] Phase 5: Concurrency Safety (JPA Pessimistic Locking).
- [x] Phase 6: Kafka Producer/Consumer (`PointsAwardedEvent`).

## 🚧 CURRENT SPRINT: Hardening & Bug Fixes (Execute these NOW)
1. **Fix Swagger 500 Error:** Diagnose and fix the `/v3/api-docs` crashing issue (ensure `springdoc-openapi-starter-webmvc-ui` v2.x is configured correctly for Spring Boot 3.4).
2. **Fix Account Filter:** Wire the `accountType` parameter correctly in `AccountController.searchAccounts` so it doesn't pass `null` to the service.
3. **Enforce Idempotency:** Apply the `@Idempotent` annotation to `AccountService.createAccount` and `updateStatus`.
4. **Test Coverage:** Write `AccountServiceImplTest` (Mockito) and `AccountControllerTest` (MockMvc). Run `mvn clean test` to verify.
5. **Git Commit:** Stage and commit these specific fixes.

## 🔮 FUTURE SPRINT (Wait for explicit user permission)
- [ ] Implement `GET /transactions` and `GET /transactions/{id}`.
- [ ] Spring Security & JWT Implementation.
- [ ] Actuator Custom Metrics (TPS, Error Rates).
- [ ] Reversal/Refund Ledger Logic.