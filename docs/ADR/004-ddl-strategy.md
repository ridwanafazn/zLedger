# ADR-004: DDL Strategy — `ddl-auto` vs Migrasi Versioned

- **Status:** Accepted (dev/base = create-drop; prod = validate + Flyway rencana)
- **Tanggal:** 2026-10-29
- **Konteks:** Aplikasi harus jalan di DB kosong tanpa skema manual, namun produksi butuh skema terkontrol.

## Keputusan
- **dev & base profile:** `spring.jpa.hibernate.ddl-auto: create-drop`.
- **prod profile:** `ddl-auto: validate` + **Flyway** (rencana Sprint berikutnya).
- Tidak ada `ddl-auto: update` (berbahaya, tidak deterministik).

## Alasan
- `create-drop` memungkinkan bootstrap cepat tanpa file migrasi saat fase pengembangan.
- Produksi wajib skema versioned & auditable → Flyway.
- `update` tidak pernah dipakai (tidak bisa rollback, tidak ada jejak).

## Konsekuensi
- `create-drop` **menghapus data saat shutdown** → hanya untuk dev.
- Sebelum deploy produksi, buat migrasi Flyway yang merepresentasikan skema entities.
- Error awal `Schema-validation: missing table [accounts]` teratasi dengan keputusan ini untuk dev.
