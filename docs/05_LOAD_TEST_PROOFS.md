# 05. Concurrency & Performance Proofs

*Bukti empiris bahwa FinLedger Engine menjaga integritas data di bawah beban konkurensi.
Angka di bawah adalah hasil eksekusi nyata di mesin lokal, bukan estimasi.*

## Lingkungan Pengujian

| Item | Nilai |
|---|---|
| Mesin | Windows 11, Docker Desktop 29 (WSL2 backend) |
| Aplikasi | Java 21 (Temurin), Spring Boot 3.4.0 |
| Database | PostgreSQL 16 (container `finledger-postgres`) |
| Redis | Redis 7 (container) |
| Kafka | Kafka 3.8 KRaft (container) |
| Alat uji | `curl --parallel` (harness `loadtest/local-loadtest.ps1`) |
| Tanggal | 10 November 2026 |

> **Catatan:** k6 tidak tersedia di mesin ini (unduhan binary terblokir jaringan),
> sehingga harness PowerShell dipakai untuk menghasilkan angka. Skrip k6 setara
> tersedia di `loadtest/k6/` dan menjadi acuan di CI.

## Skenario 1 — Flash Sale Race (saldo A_Poin terbatas)

**Setup:** satu akun dengan saldo 1.000 A_Poin, 50 request `POST /ledger/redeem`
masing-masing 100 poin dikirim **bersamaan** (bukan berurutan).

**Ekspektasi:** hanya 10 request boleh sukses (1.000 / 100), sisanya ditolak,
saldo tidak boleh negatif, tidak boleh ada error 500/deadlock.

**Hasil aktual:**

| Metrik | Hasil |
|---|---|
| `200 OK` | **10** |
| `400 Insufficient Balance` | **40** |
| `5xx` / deadlock | **0** |
| Saldo akhir | **0.0000** (tidak negatif) |
| Durasi total 50 request | 0.51 s |
| Latensi | avg 354 ms, p95 458 ms, max 465 ms |

**Kesimpulan:** pessimistic lock + urutan kunci deterministik mencegah oversell.
Sebelum perbaikan Sprint D, skenario ini menghasilkan 41 error 500
(`ObjectOptimisticLockingFailureException`) — kini nol.

## Skenario 2 — Retry Storm Idempotency

**Setup:** satu akun saldo 100.000 IDR, 50 request `POST /ledger/checkout`
identik (payload dan `X-Idempotency-Key` sama), total 10.000 IDR, dikirim bersamaan.

**Ekspektasi:** saldo hanya terpotong sekali, hanya satu jurnal terbentuk,
tidak ada error 5xx.

**Hasil aktual:**

| Metrik | Hasil |
|---|---|
| `200 OK` (termasuk dari cache) | **50** |
| `5xx` | **0** |
| Saldo akhir | **90.000** (terpotong sekali) |
| Jurnal untuk key tersebut | **1** |
| Durasi total 50 request | 0.26 s |
| Latensi | avg 150 ms, p95 177 ms, max 178 ms |

**Kesimpulan:** idempotency AOP (Redis) mengembalikan respons cache untuk retry
identik; unique constraint database menjadi lapisan kedua.

## Skenario 3 — Validasi Double-Entry

Setiap transaksi diperiksa langsung di database:

```sql
SELECT t.id,
       SUM(CASE WHEN e.entry_type='DEBIT'  THEN e.amount ELSE 0 END) AS debit,
       SUM(CASE WHEN e.entry_type='CREDIT' THEN e.amount ELSE 0 END) AS credit
FROM journal_transactions t
JOIN ledger_entries e ON e.transaction_id = t.id
GROUP BY t.id;
```

Hasil: seluruh transaksi memiliki `debit = credit` (contoh: 100000.0000 = 100000.0000).
Endpoint `GET /ledger/transactions/{id}` mengembalikan `"balanced": true`.

## Skenario 4 — Reliabilitas Event (Transactional Outbox)

**Setup:** checkout dengan tender A_POIN saat Kafka tersedia.

**Hasil:** event tercatat `PENDING` di tabel `outbox_events` dalam transaksi yang
sama dengan ledger, lalu relay mengubahnya menjadi `PUBLISHED`
(`attempts = 0`, `published_at` terisi) dalam ≤ 2 detik.

Jika Kafka mati, event tetap `PENDING` dan dicoba ulang hingga
`app.outbox.max-attempts` (default 5) sebelum ditandai `FAILED` — tidak ada event
yang hilang meski transaksi bisnis sudah commit.

## Skenario 5 — Flash Sale Voucher (Anti-Overselling)

**Setup:** voucher berkuota 10, 50 akun A_Poin (masing-masing 1.000 poin),
50 request `POST /vouchers/{id}/redeem` (biaya 100 poin) dikirim **paralel**.

**Hasil aktual:**

| Metrik | Hasil |
|---|---|
| `200 OK` | **10** |
| `400` (kuota habis / voucher tidak bisa diredeem) | **40** |
| `5xx` | **0** |
| Sisa kuota | **0 / 10** (tidak negatif) |
| Riwayat redeem | 10 baris (unik per owner) |
| Durasi total 50 request | 0.90 s |

**Kesimpulan:** kunci `PESSIMISTIC_WRITE` pada baris voucher menyerialkan
pengecekan + pengurangan kuota, sehingga tidak terjadi overselling meski
50 request tiba bersamaan. Skenario juga diuji otomatis di
`VoucherConcurrencyIntegrationTest` (kuota 5, 20 user paralel).

## Skenario 6 — Settlement Merchant & Rekonsiliasi (Fase I)

**Setup:** harness `loadtest/settlement-smoke.ps1` pada aplikasi lokal:
checkout 40.000 IDR ke merchant baru, preview settlement (fee 1%),
buat batch, replay idempotent, payout, lalu rekonsiliasi.

**Hasil aktual:**

| Langkah | Hasil |
|---|---|
| Preview: gross / fee / net | **40.000 / 400 / 39.600** |
| Create batch | `201` status `PENDING` |
| Replay `X-Idempotency-Key` sama | id batch identik (bukan duplikat) |
| Create batch periode bertumpuk (key lain) | `409 Conflict` |
| Payout | `200` status `PAID`, `payoutTransactionId` terisi |
| Payout ganda | `409 Conflict` |
| Saldo merchant setelah payout | **400.0000** (= fee), tepat |
| Rekonsiliasi | `BALANCED`, `discrepancyCount = 0` |
| Rekonsiliasi setelah data smoke dibersihkan | `BALANCED`, `discrepancyCount = 0` |

**Kesimpulan:** payout settlement menghasilkan jurnal `SETTLEMENT` yang seimbang
(DEBIT merchant = CREDIT clearing) dan tidak mengubah saldo total sistem.
Rekonsiliasi memvalidasi `balance = opening_balance + ΣCREDIT − ΣDEBIT` untuk
seluruh akun sehingga drift sekecil apa pun akan terdeteksi. Uji otomatis setara:
`SettlementIntegrationTest` (3 skenario) dan `ReconciliationServiceImplTest`.

## Limitasi

- Pengujian dijalankan pada satu mesin lokal (bukan cluster), sehingga angka
  throughput bukan benchmark produksi.
- Voucher sudah dimodelkan; skenario 5 menguji kuota nyata.
- Skenario 1.000 VU seperti rencana awal memerlukan k6 dan lingkungan terpisah
  (lihat `loadtest/k6/` untuk skrip yang siap dijalankan di CI/load generator).
- Settlement belum diuji dengan volume transaksi besar (rincian item dibaca
  dalam satu query per batch; perlu pagination bila periode sangat panjang).

## Cara Menjalankan Ulang

```powershell
# aplikasi + docker compose harus aktif
powershell -ExecutionPolicy Bypass -File loadtest/local-loadtest.ps1 -Parallel 50
powershell -ExecutionPolicy Bypass -File loadtest/voucher-loadtest.ps1 -Users 50 -Quota 10
powershell -ExecutionPolicy Bypass -File loadtest/settlement-smoke.ps1

# dengan k6 (jika tersedia)
k6 run -e VUS=50 -e ITERATIONS=200 loadtest/k6/flash-sale.js
k6 run -e VUS=20 -e ITERATIONS=20  loadtest/k6/idempotency.js
```
