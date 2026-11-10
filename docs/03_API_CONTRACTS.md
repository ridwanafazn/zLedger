# 03. Mobile-Optimized API Contracts

All endpoints are prefixed with `/api/v1`.

## 1. Core Endpoints
- `POST /accounts`: Create a new ledger wallet.
- `GET /accounts/{id}/balance`: Fetch current wallet balance.
- `POST /ledger/checkout`: Process a multi-tender transaction.
- `POST /ledger/flash-sale/redeem`: High-concurrency voucher redemption.
- `GET /transactions/{accountId}`: Paginated ledger audit history.

## 2. The Idempotency Promise
Mobile devs MUST include the `X-Idempotency-Key` header on all `POST` and `PATCH` requests. 
If a network timeout occurs, the mobile app can safely resend the exact same request with the same key. The backend will intercept it via Redis AOP and return the cached success response without double-charging the user's account.

## 3. Standardized Error Handling (RFC 7807)
Never parse random error strings again. Every error returns a consistent JSON:
```json
{
  "type": "[https://api.finledger.com/errors/insufficient-balance](https://api.finledger.com/errors/insufficient-balance)",
  "title": "Insufficient Balance",
  "status": 422,
  "detail": "Account A-Poin balance is 500, but transaction requires 1000.",
  "instance": "/api/v1/ledger/checkout",
  "errorCode": "ERR_LEDGER_001"
}