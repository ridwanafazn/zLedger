# ADR-003: Shape Request `/redeem`

- **Status:** Accepted (dengan rencana deprecation shape lama)
- **Tanggal:** 2026-10-29
- **Konteks:** Endpoint `/redeem` saat ini menerima input via **query params** (`ownerId`, `voucherId`, `points`, `idempotencyKey`).

## Keputusan
- **v1 (sekarang):** pertahankan query params untuk kompatibilitas; dukungan body JSON ditandai **belum**.
- **v1.1 (rencana):** migrasi ke **body JSON** (`RedeemRequest`), query params deprecated dan dihapus di v2.

## Alasan
- Konsistensi: endpoint write lain (`checkout`) memakai body JSON.
- Query params menyulitkan validasi kompleks & ekspansi field (mis. `maxPrice`, `idempotencyKey` di header).
- Body JSON mejor mengakomodasi `@Valid` nested object.

## Konsekuensi
- FE sebaiknya dibangun agar mudah pindah: pisahkan pembentukan parameter dari pemanggilan HTTP.
- Berikan header `Deprecation`/`Sunset` saat query params dipakai (setelah v1.1 dirilis).

## Bentuk Target (v1.1)
```json
{
  "ownerId": "cust-001",
  "voucherId": "550e8400-e29b-41d4-a716-446655440000",
  "points": 50000
}
```
`idempotencyKey` pindah ke header `X-Idempotency-Key`.
