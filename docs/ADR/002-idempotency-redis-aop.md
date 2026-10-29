# ADR-002: Idempotency via AOP + Redis

- **Status:** Accepted
- **Tanggal:** 2026-10-29
- **Konteks:** Client dapat mengirim ulang request (network retry, double-click) yang berpotensi menggandakan transaksi finansial.

## Keputusan
Custom annotation `@Idempotent` + Spring AOP `IdempotencyAspect` menggunakan Redis `SET NX` (setIfAbsent) dengan TTL. Key diambil dari header `X-Idempotency-Key`.

## Alasan
- Deklaratif (`@Idempotent`) → bersih, tidak mencampur logika idempotency ke service.
- Redis atomic `SETNX` menjamin hanya satu request yang lolos.
- TTL (default 86400s) mencegah akumulasi key tak terbatas.

## Konsekuensi
- **Positif:** Retry aman, mencegah double deduction.
- **Negatif:** Butuh Redis available; bila Redis down → fail-closed (tolak) demi keamanan finansial.
- Key sama + body berbeda harus menghasilkan `409 IDEMPOTENCY_CONFLICT`, bukan diam-diam mengembalikan response lama.
- Bila method gagal, key dihapus agar client dapat retry.

## Catatan Environment
`spring.data.redis.password` harus **absen/null** bila Redis tanpa password. Nilai kosong (`""`) membuat Redisson mengirim `AUTH ""` dan ditolak Redis.
