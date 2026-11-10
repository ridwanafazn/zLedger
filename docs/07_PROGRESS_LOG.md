# 07. Progress Log & Handover

> Dokumen ini adalah **sumber kebenaran status proyek** agar pekerjaan tidak hilang
> saat sesi/agent berganti. Perbarui setiap akhir fase.

## Cara Melanjutkan (untuk agent berikutnya)

1. Baca `docs/04_AGENT_TASKS.md` (roadmap) dan dokumen ini.
2. Jalankan infrastruktur: `docker compose up -d`.
3. Jalankan aplikasi: `mvn spring-boot:run` (port 8080).
4. Login: `POST /api/v1/auth/login` (`admin/admin123`).
5. Registrasi user baru: `POST /api/v1/auth/register`.
6. Jalankan test: `mvn test` (unit, tanpa Docker) / `mvn verify` (butuh Docker).
7. Cek status fase di tabel bawah; lanjutkan dari fase berstatus 🚧.

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
| H | User DB + registrasi + revokasi token | ✅ | `231c7a8`…`311514b` |
| I | Settlement & reconciliation | ✅ | `5d1f03b` |
| J | Blob storage / bukti bayar | ✅ | commit Fase J+K |
| K | Observability (Prometheus/Grafana) | ✅ | commit Fase J+K |
| L | Deploy cloud | ⏸️ ditunda | — |

## Keputusan Teknis Penting

| Topik | Keputusan | Alasan |
|---|---|---|
| Idempotency | AOP Redis + fingerprint payload + cache respons; scope `Class.method` | Retry identik harus mengembalikan respons sukses, bukan 409 |
| Locking | `flush()` → detach → reload `PESSIMISTIC_WRITE` → salin state; urut UUID | Menghindari optimistic failure & deadlock |
| Enum PostgreSQL | `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` | Kolom native enum menolak varchar |
| Kafka | Transactional Outbox + relay terjadwal | Tidak ada event hilang saat commit |
| Auth | JWT HS256 + refresh token opaque di Redis (rotasi) | Stateless + revokasi |
| User store | Tabel `app_users` + `app_user_roles`, BCrypt; seed konfigurasi hanya saat user belum ada | Registrasi nyata & audit; kredensial demo tetap jalan |
| Revokasi akses | Redis `finledger:auth:revoked:{username}` + `revokeAll` refresh token | Disable user berlaku seketika, tanpa menunggu JWT kedaluwarsa |
| Registrasi publik | Role dikunci `CUSTOMER`; MERCHANT/ADMIN hanya lewat admin | Mencegah privilege escalation |
| Error | RFC 7807 `application/problem+json` | Kontrak FE konsisten |
| Reversal | Jurnal `REFUND` kompensasi, bukan UPDATE/DELETE | Immutability audit trail |
| Settlement | Snapshot `PENDING` → payout jurnal `SETTLEMENT` (DEBIT merchant, CREDIT clearing) | Payout = perpindahan dana nyata, dapat diaudit sebagai jurnal biasa |
| Anti-overlap settlement | `pg_advisory_xact_lock(hashtext(merchant:currency))` + cek rentang `[start, end)` | Dua admin paralel tidak bisa membuat periode bertumpuk; batch CANCELLED membebaskan periode |
| Fee settlement | `(gross − refund) × fee%`, skala 4 `HALF_UP`, default 1% (`app.settlement.fee-percent`) | Pembulatan deterministik & dapat dikonfigurasi |
| Rekonsiliasi | `opening_balance` + `ΣCREDIT − ΣDEBIT`, hasil disimpan sebagai run + temuan | Saldo awal seed tidak lagi menghasilkan selisih palsu; audit trail permanen |
| Bukti bayar | Proxy upload multipart + validasi magic bytes + SHA-256, blob di object storage | File divalidasi sebelum disimpan; tidak ada objek yatim; port `BlobStorage` tetap siap untuk presigned upload |
| Presigned upload | Metadata `PENDING` + PUT bertanda tangan (S3), verifikasi ukuran/tipes/checksum saat complete | File besar tidak membebani aplikasi, tetapi tetap divalidasi server sebelum dipakai |
| Retensi bukti bayar | Job harian: hapus `UPLOADED` > N hari + `PENDING` kedaluwarsa, termasuk objek storage | Tidak ada byte yatim; kepatuhan retensi dapat diatur via konfigurasi |
| Alerting | 8 aturan Prometheus (availability, 5xx, latency, rekonsiliasi, settlement, bukti bayar, Hikari) | Masalah finansial/kapasitas terdeteksi otomatis, bukan dari dashboard manual |
| Object storage | Port `BlobStorage`; default `local`, `s3` untuk MinIO/S3 (S3Mock di compose karena image MinIO tidak tersedia di env ini) | Kode produksi sama dengan demo; tanpa Docker tetap bisa diuji |
| Metrik | Micrometer Prometheus + metrik bisnis berprefix `finledger.*` | Satu sumber kebenaran untuk alert & dashboard, tanpa agent tambahan |
| Test | Surefire unit, Failsafe integration | `mvn test` tetap jalan tanpa Docker |

## Lingkungan Lokal

- Java 21 (Temurin), Maven 3.10, Docker Desktop 29.
- PostgreSQL 16, Redis 7, Kafka 3.8 (KRaft), S3Mock, Prometheus, Grafana via `docker-compose.yml`.
- Testcontainers kini berjalan lokal **dan** di CI dengan system property
  `api.version=1.43` (Docker modern menolak API < 1.40).
- k6 tidak tersedia; harness `loadtest/local-loadtest.ps1` dan
  `loadtest/voucher-loadtest.ps1` dipakai untuk bukti konkurensi.

## Known Issues / Utang Teknis

1. `GET /accounts/{id}/mutations` tidak memvalidasi akun ada/tidak (mengembalikan halaman kosong).
2. Idempotency `redeem` (legacy) hanya efektif bila klien mengirim header.
3. SpotBugs report-only (belum fail build).
4. Belum ada endpoint ganti password/reset password (registrasi & admin create sudah ada).
5. JWT tidak membawa `jti`; revokasi berbasis username (cukup untuk skala portofolio).
6. Settlement membaca seluruh item jurnal satu periode dalam satu query (belum paginated);
   untuk merchant volume besar sebaiknya ditambah preview berbasis agregasi SQL.
7. Payout belum memakai jurnal reversal untuk "batalkan payout" (hanya reversal manual admin
   lewat `POST /ledger/transactions/{id}/reverse`).
8. Upload multipart mem-buffer file di memori (maksimum 5 MB). File yang lebih besar
   harus memakai jalur presigned (`presign` → PUT → `complete`), yang hanya tersedia
   saat `app.storage.type=s3`.
9. `/actuator/prometheus` terbuka tanpa autentikasi; di produksi batasi lewat network policy
   atau port manajemen terpisah.
10. Alert masih tampil di Prometheus (`/alerts`); belum ada Alertmanager/Slack routing.
11. Retensi berjalan harian pada satu instance; bila aplikasi di-scale-out, job perlu
    distributed lock (mis. ShedLock) agar tidak berjalan ganda.

## Riwayat Perubahan

| Tanggal | Perubahan |
|---|---|
| 2026-11-10 | Fase A–E selesai; 93 test hijau; push `80c9cbe` |
| 2026-11-10 | Fase F: fix CI (`api.version=1.43`), JaCoCo 50% (aktual 80.9%), ArchUnit 4 rule, artefak laporan |
| 2026-11-10 | Fase G: domain voucher (kuota atomik, unique redeem per user), 114 unit + 6 integration hijau, bukti 50 paralel kuota 10 |
| 2026-11-10 | OWASP Dependency-Check dipindah ke `security-scan.yml` (mingguan/manual + cache + NVD_API_KEY) agar CI push tetap cepat |
| 2026-11-10 | Fase H: user DB (V6 `app_users`/`app_user_roles`), registrasi publik, CRUD user admin, revokasi token seketika, 135 unit + 8 integration hijau, coverage 86.5% |
| 2026-11-10 | Fix config: `springdoc:`/`management:` yang termakan komentar di `application.yml`; hapus `spring.profiles.active` ilegal dari `application-prod.yml` (profil prod sudah diuji start & health UP) |
| 2026-11-10 | Hardening: `logout` dapat dipanggil tanpa access token (cukup refresh token); 135 unit + 9 integration hijau |
| 2026-11-10 | Fase I: settlement merchant (V8 `merchant_settlements`/`settlement_items`) + rekonsiliasi audit (V7 `opening_balance`, `reference_type.SETTLEMENT`), endpoint ADMIN, 165 unit + 12 integration hijau, smoke HTTP 15/15 lulus |
| 2026-11-10 | Fase J: bukti bayar (V9 `payment_proofs`), port `BlobStorage` (local/S3), validasi magic bytes + SHA-256, streaming unduh, presigned URL; smoke 21/21 lulus (termasuk jalur S3 nyata ke S3Mock) |
| 2026-11-10 | Fase K: Micrometer Prometheus (`/actuator/prometheus`), 12 panel Grafana auto-provisioned, metrik bisnis settlement/rekonsiliasi/bukti bayar, Prometheus target UP |
| 2026-11-10 | Backlog J/K tuntas: V10 (`status`/`upload_method`/`expires_at`), presigned upload (presign → PUT → complete, verifikasi checksum server), retensi harian + endpoint manual, 8 alert rules Prometheus; smoke presign 14/14 (file 3 MB langsung ke S3) |
| 2026-11-10 | Fix config: `application-dev.yml` menimpa `management.endpoints.web.exposure.include` tanpa `prometheus` (CI `SPRING_PROFILES_ACTIVE=dev` mengembalikan 500 pada `/actuator/prometheus`); ditangkap CI run `42772889107` |

## Catatan CI

- **Status: hijau** (run `42773495899`, 4m14s) untuk commit Fase J+K `e26fc9c`.
- Run hijau sebelumnya: `42766358423` (Fase I), `42763365595` (dokumentasi Fase H), `42763170381` (logout), `42762838986` (Fase H final).
- Run **gagal** `42772889107` menemukan bug konfigurasi nyata: `application-dev.yml`
  menimpa `management.endpoints.web.exposure.include` tanpa `prometheus`
  (`SPRING_PROFILES_ACTIVE=dev` dipakai CI), sehingga `/actuator/prometheus` → 500.
  Diperbaiki di `e26fc9c` dan dijaga oleh regression test.
- `ci.yml` (setiap push): unit + integration + SpotBugs + JaCoCo. Target < 5 menit.
- Uji lokal terakhir: **185 unit + 14 integration**, JaCoCo gate 50% terpenuhi.
- `security-scan.yml` (mingguan/manual/perubahan `pom.xml`): OWASP Dependency-Check.
  - Tanpa secret `NVD_API_KEY`, scan **dilewati** (dependency-check mengunduh
    421.868 record NVD dan bisa 30–60 menit).
  - Tambahkan secret `NVD_API_KEY` di repo untuk mengaktifkan.
  - Database NVD dan plugin di-cache lewat `actions/cache`.
