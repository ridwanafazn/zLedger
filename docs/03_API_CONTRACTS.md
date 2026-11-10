# 03. Mobile-Optimized API Contracts

All endpoints are prefixed with `/api/v1`.

## 1. Core Endpoints
- `POST /accounts`: Create a new ledger wallet.
- `GET /accounts/{id}`: Account detail.
- `GET /accounts/{id}/balance`: Fetch current wallet balance.
- `PATCH /accounts/{id}/status`: Change account status (ACTIVE/SUSPENDED/CLOSED).
- `GET /accounts`: Search with filters + pagination.
- `POST /ledger/checkout`: Process a multi-tender transaction.
- `POST /ledger/flash-sale/redeem`: High-concurrency voucher redemption.
- `GET /ledger/transactions`: Paginated journal list (filters: `referenceType`, `accountId`, `from`, `to`).
- `GET /ledger/transactions/{transactionId}`: Transaction detail with debit/credit entries.
- `POST /ledger/transactions/{transactionId}/reverse`: Reversal/refund via compensating journal (ADMIN).
- `GET /accounts/{accountId}/mutations`: Per-account audit history (paginated).
- `POST /vouchers`, `GET /vouchers`, `GET /vouchers/{id}`, `PATCH /vouchers/{id}/status`, `POST /vouchers/{id}/redeem`.
- `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`, `GET /auth/me`.
- `POST /users`, `GET /users`, `GET /users/{id}`, `PATCH /users/{id}/status` (ADMIN).
  Registrasi publik selalu membuat role CUSTOMER; role lain hanya lewat endpoint admin.
  Menonaktifkan user mencabut refresh token dan access token aktifnya.
- Settlement (ADMIN): `GET /settlements/preview`, `POST /settlements`,
  `GET /settlements`, `GET /settlements/{id}`, `POST /settlements/{id}/pay`,
  `POST /settlements/{id}/cancel`.
- Rekonsiliasi (ADMIN): `POST /reconciliation/runs`, `GET /reconciliation/runs`,
  `GET /reconciliation/runs/{id}`.
- Bukti bayar: `POST /payment-proofs` (multipart, CUSTOMER/MERCHANT/ADMIN),
  `POST /payment-proofs/presign` (URL PUT untuk file besar),
  `POST /payment-proofs/{id}/complete` (verifikasi objek),
  `GET /payment-proofs?transactionId=`, `GET /payment-proofs/{id}`,
  `GET /payment-proofs/{id}/content` (streaming), `DELETE /payment-proofs/{id}` (ADMIN),
  `POST /payment-proofs/retention/run` (ADMIN).
- Observability: `GET /actuator/health`, `/actuator/metrics`, `/actuator/prometheus`.
- CORS: origin frontend diatur via `app.cors.allowed-origins`.

## 1b. Aturan Idempotency per Endpoint

`X-Idempotency-Key` wajib pada semua mutasi data, **kecuali** endpoint auth
(`register`, `login`, `refresh`, `logout`) karena endpoint tersebut sudah
idempoten secara alami (registrasi duplikat → `409`, rotasi token sekali pakai).

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