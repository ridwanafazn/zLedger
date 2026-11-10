# 05. Concurrency & Performance Proofs (To Be Generated)

*This document serves as empirical evidence for tech recruiters that FinLedger Engine can handle enterprise-scale loads without data corruption.*

## Scenario 1: The "Flash Sale" Race Condition Test
**Setup:**
- 1 Voucher available. 
- 1,000 concurrent virtual users (via `k6` or `Locust`) attempt to redeem it at the exact same millisecond.

**Expected Outcome:**
- 1 request gets `200 OK`.
- 999 requests get `422 Unprocessable Entity` (Insufficient Quota/Balance).
- **Zero Database Deadlocks.** (Thanks to Redis Redisson locks & JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)`).
- **Ledger Integrity:** Total A-Poin deducted equals exactly 1 voucher cost.

## Scenario 2: Network Drop Retry Test (Idempotency)
**Setup:**
- Fire 5 identical checkout requests (same `X-Idempotency-Key`) within 100ms.

**Expected Outcome:**
- The first request processes the transaction.
- Requests 2-5 are intercepted by the `@Idempotent` AOP aspect and return the cached `200 OK` response.
- **Ledger Integrity:** User balance is only deducted ONCE.

*(Note for Agent: Help the user generate the `k6` scripts for these scenarios when we reach the QA phase).*