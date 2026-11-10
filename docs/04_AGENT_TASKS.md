# 04. Agent Execution Roadmap & Tasks

**Dear Autonomous Agent (Hermes/DeepSeek),**
You are a Principal Java Engineer. Read the current environment state carefully before modifying code.

## ✅ COMPLETED (Do NOT touch or recreate)
- [x] Phase 1: Spring Boot 3.4.0 & Java 21 Scaffolding.
- [x] Phase 2: PostgreSQL, Redis, Kafka via Docker Compose.
- [x] Phase 3: Core Ledger Domain Entities & Flyway (V1 & V2).
- [x] Phase 4: `Account` & `Ledger` Services & Controllers.
- [x] Phase 5: Concurrency Safety (JPA Pessimistic Locking + deterministic lock order).
- [x] Phase 6: Kafka Producer/Consumer (`PointsAwardedEvent`).

## ✅ HARDENING SPRINTS (done)
- [x] Swagger `/v3/api-docs` fixed (springdoc 2.7.0).
- [x] Account filter wiring + strict enum validation (400).
- [x] Idempotency enforced on Account & Ledger mutations (cached response + fingerprint).
- [x] Test coverage: account service/controller, ledger locking/status guard.
- [x] RFC 7807 problem details, correlation ID, OpenAPI metadata, checkout metrics.
- [x] `GET /transactions` and `GET /transactions/{id}`.
- [x] Kafka healthcheck fixed; integration tests isolated via Failsafe.
- [x] Spring Security + JWT + RBAC (`/api/v1/auth/login`, role ADMIN/MERCHANT/CUSTOMER).
- [x] Transactional Outbox untuk reliabilitas Kafka.
- [x] k6 + harness konkurensi lokal; bukti di `docs/05_LOAD_TEST_PROOFS.md`.
- [x] SpotBugs + OWASP Dependency-Check profile di CI.
- [x] Sprint E lokal: reversal/refund, mutasi paginated, filter transaksi, CORS, refresh token.
- [x] Fase F: CI green (docker `api.version`), JaCoCo >= 50% (aktual 80.9%), ArchUnit.
- [x] Fase G: domain voucher (katalog, kuota atomik, anti-overselling, unique redeem).

## 🔮 FUTURE SPRINT (Wait for explicit user permission)
- [ ] Fase H: User DB + registrasi.
- [ ] Fase I: Settlement & reconciliation.
- [ ] Fase J: Blob storage / bukti bayar.
- [ ] Fase K: Observability (Prometheus/Grafana).
- [ ] Fase L: Cloud deployment.