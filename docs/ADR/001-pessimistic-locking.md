# ADR-001: Account Lookup dengan Pessimistic Write Lock

- **Status:** Accepted
- **Tanggal:** 2026-10-29
- **Konteks:** Operasi mutasi saldo (checkout, redeem) harus tahan terhadap race condition concurrency tinggi.

## Keputusan
Gunakan `@Lock(LockModeType.PESSIMISTIC_WRITE)` pada `AccountRepository.findById(UUID)` untuk mengunci baris akun selama transaksi.

## Alasan
- Menjamin serialisasi mutasi saldo per-akun tanpa retry loop.
- Lebih sederhana dari optimistic lock + retry pada skenario write-heavy (flash sale).

## Konsekuensi
- **Positif:** Tidak ada lost update; double-entry konsisten.
- **Negatif:** Potensi lock contention/deadlock bila urutan lock antar-akun tidak konsisten. Mitigasi: kunci akun dengan urutan deterministik (mis. berdasarkan UUID menaik).
- Nama metode harus `findById` (bukan derived query `findByIdForUpdate`) karena Spring Data mem-parsing nama method; `findByIdForUpdate` menyebabkan `PropertyReferenceException`.
- `lock.timeout` di-set 5000ms via `@QueryHints`.
