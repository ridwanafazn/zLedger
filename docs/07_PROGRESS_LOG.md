# 07. Progress Log & Handover

> Dokumen ini adalah **sumber kebenaran status proyek** agar pekerjaan tidak hilang
> saat sesi/agent berganti. Perbarui setiap akhir fase.

## Cara Melanjutkan (untuk agent berikutnya)

1. Baca `docs/04_AGENT_TASKS.md` (roadmap) dan dokumen ini.
2. Jalankan infrastruktur: `docker compose up -d`.
3. Jalankan aplikasi: `mvn spring-boot:run` (port 8080).
4. Login: `POST /api/v1/auth/login` (`admin/admin123`).
5. Jalankan test: `mvn test` (unit, tanpa Docker) / `mvn verify` (butuh Docker).
6. Cek status fase di tabel bawah; lanjutkan dari fase berstatus 🚧.

## Status Fase

| Fase | Nama | Status | Commit |
|---|---|---|---|
| 1–7 | Core ledger, Kafka, controller, test | ✅ | `fb26b7a` |
| A | Account CRUD + idempotency + test | ✅ | `406d9bc`…`ab91bbc` |
| B | Locking deterministik + guard status | ✅ | `09cd62f` |
| C | RFC 7807, idempotency sejati, transaksi, observability | ✅ | `355a570`…`49e63c1` |
| D | JWT/RBAC, Outbox, load proof, CI/static analysis | ✅ | `b3f2592`…`302cda2` |
| E | Reversal, mutasi paginated, filter, CORS, refresh token | ✅ | `80c9cbe` |
| F | CI green + quality gate (JaCoCo, ArchUnit) | ✅ | `72a3251` + perbaikan `api.version` |
| G | Domain voucher & flash sale nyata | ✅ | commit Fase G |
| H | User DB + registrasi | ⏳ | — |
| I | Settlement & reconciliation | ⏳ | — |
| J | Blob storage / bukti bayar | ⏳ | — |
| K | Observability (Prometheus/Grafana) | ⏳ | — |
| L | Deploy cloud | ⏸️ ditunda | — |

## Keputusan Teknis Penting

| Topik | Keputusan | Alasan |
|---|---|---|
| Idempotency | AOP Redis + fingerprint payload + cache respons; scope `Class.method` | Retry identik harus mengembalikan respons sukses, bukan 409 |
| Locking | `flush()` → detach → reload `PESSIMISTIC_WRITE` → salin state; urut UUID | Menghindari optimistic failure & deadlock |
| Enum PostgreSQL | `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` | Kolom native enum menolak varchar |
| Kafka | Transactional Outbox + relay terjadwal | Tidak ada event hilang saat commit |
| Auth | JWT HS256 + refresh token opaque di Redis (rotasi) | Stateless + revokasi |
| Error | RFC 7807 `application/problem+json` | Kontrak FE konsisten |
| Reversal | Jurnal `REFUND` kompensasi, bukan UPDATE/DELETE | Immutability audit trail |
| Test | Surefire unit, Failsafe integration | `mvn test` tetap jalan tanpa Docker |

## Lingkungan Lokal

- Java 21 (Temurin), Maven 3.10, Docker Desktop 29.
- PostgreSQL 16, Redis 7, Kafka 3.8 (KRaft) via `docker-compose.yml`.
- Testcontainers kini berjalan lokal **dan** di CI dengan system property
  `api.version=1.43` (Docker modern menolak API < 1.40).
- k6 tidak tersedia; harness `loadtest/local-loadtest.ps1` dan
  `loadtest/voucher-loadtest.ps1` dipakai untuk bukti konkurensi.

## Known Issues / Utang Teknis

1. User masih dari konfigurasi (`app.security.users`) → Fase H.
2. Settlement/reconciliation belum ada → Fase I.
3. Upload bukti bayar belum ada → Fase J.
4. `GET /accounts/{id}/mutations` tidak memvalidasi akun ada/tidak (mengembalikan halaman kosong).
5. Idempotency `redeem` (legacy) hanya efektif bila klien mengirim header.
6. SpotBugs report-only (belum fail build).

## Riwayat Perubahan

| Tanggal | Perubahan |
|---|---|
| 2026-11-10 | Fase A–E selesai; 93 test hijau; push `80c9cbe` |
| 2026-11-10 | Fase F: fix CI (`api.version=1.43`), JaCoCo 50% (aktual 80.9%), ArchUnit 4 rule, artefak laporan |
| 2026-11-10 | Fase G: domain voucher (kuota atomik, unique redeem per user), 114 unit + 6 integration hijau, bukti 50 paralel kuota 10 |
| 2026-11-10 | OWASP Dependency-Check dipindah ke `security-scan.yml` (mingguan/manual + cache + NVD_API_KEY) agar CI push tetap cepat |

## Catatan CI

- **Status: hijau** (run `42759043214`, 5m1s) dengan artefak unit test,
  integration test, SpotBugs, dan JaCoCo.
- `ci.yml` (setiap push): unit + integration + SpotBugs + JaCoCo. Target < 5 menit.
- `security-scan.yml` (mingguan/manual/perubahan `pom.xml`): OWASP Dependency-Check.
  - Tanpa secret `NVD_API_KEY`, scan **dilewati** (dependency-check mengunduh
    421.868 record NVD dan bisa 30–60 menit).
  - Tambahkan secret `NVD_API_KEY` di repo untuk mengaktifkan.
  - Database NVD dan plugin di-cache lewat `actions/cache`.
