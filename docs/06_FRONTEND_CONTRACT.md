# 06. Frontend Integration Contract

Dokumen ini merangkum apa yang dibutuhkan aplikasi mobile/web untuk berjalan penuh
di atas FinLedger Engine, serta status kesiapannya.

## 1. Alur Autentikasi

```
POST /api/v1/auth/login      → { accessToken, refreshToken, expiresInSeconds, roles }
POST /api/v1/auth/refresh    → { accessToken, refreshToken }  (rotasi, sekali pakai)
POST /api/v1/auth/logout     → 204 (refresh token dicabut)
GET  /api/v1/auth/me         → { username, roles }
```

Aturan untuk frontend:
- Simpan `accessToken` di memori/secure storage, `refreshToken` di secure storage.
- Panggil `/auth/refresh` saat access token mendekati kedaluwarsa (60 menit) atau saat menerima `401`.
- Setiap `POST`/`PATCH` wajib mengirim `X-Idempotency-Key` (UUIDv4 baru per aksi user).
- Teruskan `X-Request-ID` dari response bila melapor bug (membantu tracing log).

## 2. Peta Kebutuhan Layar → Endpoint

| Layar | Endpoint |
|---|---|
| Login | `POST /auth/login` |
| Dashboard saldo | `GET /accounts/{id}/balance`, `GET /accounts?ownerId=` |
| Riwayat mutasi (infinite scroll) | `GET /accounts/{id}/mutations?page=&size=` |
| Daftar transaksi + filter tanggal/jenis | `GET /transactions?referenceType=&accountId=&from=&to=&page=&size=` |
| Detail transaksi | `GET /transactions/{id}` |
| Checkout multi-tender | `POST /ledger/checkout` |
| Redeem voucher | `POST /ledger/redeem` |
| Ajukan refund (admin) | `POST /transactions/{id}/reverse` |
| Manajemen akun (admin) | `POST /accounts`, `PATCH /accounts/{id}/status` |

## 3. Bentuk Response

**Pagination (seragam):**

```json
{
  "content": [],
  "page": { "number": 0, "size": 20, "totalElements": 0, "totalPages": 0,
            "first": true, "last": true, "numberOfElements": 0 },
  "sort": [{ "field": "createdAt", "direction": "DESC" }]
}
```

**Error (RFC 7807):**

```json
{
  "type": "https://api.finledger.com/errors/insufficient-balance",
  "title": "Insufficient Balance",
  "status": 400,
  "detail": "Balans A_POIN tidak mencukupi...",
  "instance": "/api/v1/ledger/checkout",
  "errorCode": "BALANCE_INSUFFICIENT",
  "errors": null
}
```

## 4. CORS

Origin yang diizinkan diatur dengan `CORS_ALLOWED_ORIGINS`
(default: `http://localhost:3000,http://localhost:5173`). Header yang diekspos:
`X-Request-ID`, `Location`.

## 5. Yang Belum Tersedia (Roadmap)

| Kebutuhan FE | Status |
|---|---|
| Registrasi user mandiri | ❌ belum (user inti dari konfigurasi) |
| Katalog voucher & redeem | ✅ (`/api/v1/vouchers`) |
| Notifikasi/websocket status pembayaran | ❌ belum |
| Endpoint upload bukti bayar | ❌ belum (rencana S3/MinIO) |
| Laporan settlement merchant | ❌ belum |
| Rekonsiliasi saldo vs ledger (admin) | ⚠️ tersedia lewat SQL, belum endpoint |

## 6. Checklist Integrasi FE

- [x] Login, refresh, logout, profil
- [x] Balance & pencarian akun
- [x] Pagination mutasi
- [x] Filter transaksi (jenis, akun, rentang tanggal)
- [x] Detail transaksi dengan verifikasi `balanced`
- [x] Checkout & redeem (idempotent)
- [x] Reversal (admin)
- [x] CORS
- [x] Error contract konsisten + correlation ID
- [ ] Registrasi & manajemen user penuh
- [x] Voucher catalog/quota
- [ ] Upload bukti bayar
