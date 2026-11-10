# Keputusan Teknis: Blob Storage & Bukti Bayar (Fase J)

- **Status:** Implemented
- **Terkait:** [ADR-005](ADR/005-blob-storage.md)
- **Tanggal:** 2026-11-10

## Ringkasan

Bukti bayar (foto transfer, resi, PDF) disimpan di **object storage S3-compatible**
di balik port `BlobStorage`. PostgreSQL hanya menyimpan metadata + checksum SHA-256.

```
Client ──multipart──▶ PaymentProofController ──▶ PaymentProofService ──▶ BlobStorage
                                │                        │                    │
                                ▼                        ▼                    ▼
                        MD5/tipe/signature        payment_proofs         local FS / S3
```

## Penyimpangan dari ADR-005 (dan alasannya)

ADR-005 mengusulkan **presigned upload langsung** (byte tidak melewati aplikasi).
Implementasi memilih **proxy upload lewat API** sebagai jalur utama:

| Aspek | Presigned upload | Proxy upload (dipilih) |
|---|---|---|
| Validasi isi | Klien bisa mengunggah apa pun; validasi hanya setelah file mendarat | File divalidasi **sebelum** disimpan (magic bytes, ukuran, tipe) |
| Objek yatim | Perlu lifecycle rule / cron pembersih | Tidak ada: metadata & blob dibuat dalam satu alur |
| Auth | Harus menerbitkan URL bertanda tangan per request | Cukup JWT yang sudah ada |
| Beban server | Rendah untuk file besar | Terbatas oleh `max-file-size` (default 5 MB) |
| Portabilitas | Perlu SDK presigner di klien | Klien cukup `multipart/form-data` |

Untuk bukti bayar berukuran ≤ 5 MB, keamanan dan kesederhanaan lebih bernilai
daripada penghematan bandwidth. Untuk file yang lebih besar, aplikasi menyediakan
jalur **presigned upload** (metadata `PENDING` → PUT langsung ke storage →
`complete` dengan verifikasi ukuran/tipe/checksum), sehingga byte tidak pernah
melewati aplikasi namun tetap tervalidasi sebelum dipakai.

## Kontrol keamanan

1. **Whitelist MIME:** `image/png`, `image/jpeg`, `image/webp`, `application/pdf`
   (`app.storage.allowed-content-types`).
2. **Magic bytes:** `FileSignatureValidator` memastikan isi file benar-benar sesuai
   tipe yang diklaim (mencegah `evil.exe` berlabel `image/png`).
3. **Batas ukuran:** `app.storage.max-file-size-bytes` (default 5 MB) + batas
   multipart Spring (`spring.servlet.multipart.max-file-size`).
4. **Integritas:** SHA-256 dihitung saat unggah, disimpan, dan unik per transaksi
   (`uk_payment_proofs_checksum`) sehingga file sama tidak bisa diunggah dua kali.
5. **Otorisasi:** hanya uploader dan ADMIN yang bisa melihat/mengunduh; DELETE khusus ADMIN.
6. **Path traversal:** storage lokal menolak key absolut/`..`.

## Konfigurasi

| Env | Default | Keterangan |
|---|---|---|
| `STORAGE_TYPE` | `local` | `local` (filesystem) atau `s3` |
| `STORAGE_BUCKET` | `finledger-proofs` | Bucket; dibuat otomatis bila belum ada |
| `STORAGE_ENDPOINT` | `http://localhost:9000` | Endpoint S3-compatible |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | kosong | Wajib saat `s3` |
| `STORAGE_LOCAL_PATH` | `./target/blob-store` | Direktori storage lokal |
| `STORAGE_MAX_FILE_SIZE` | `5242880` | Batas ukuran file (byte) |
| `STORAGE_PRESIGNED_TTL` | `300` | TTL URL unduh presigned (detik) |

## Catatan lingkungan

Image `minio/minio` tidak dapat diunduh pada environment pengembangan ini
(`pull access denied`; binary community di `dl.min.io` juga mengembalikan **410 Gone**
karena MinIO beralih ke AIStor berlisensi), sehingga `docker-compose.yml` memakai
**adobe/s3mock** (port host **9091**) sebagai backend S3-compatible untuk demo.
Untuk MinIO:

```yaml
minio:
  image: minio/minio
  command: server /data --console-address ":9001"
  ports: ["9000:9000", "9001:9001"]
```

lalu set `STORAGE_ENDPOINT=http://localhost:9000`. Kode aplikasi identik karena
keduanya memakai S3 API yang sama. Alternatif open-source yang masih dapat diunduh:
**Garage** (`dxflrs/garage`), **SeaweedFS** (`chrislusf/seaweedfs`), atau **Ceph RGW**.

## Presigned upload (file besar)

```
POST /api/v1/payment-proofs/presign   → { proofId, uploadUrl, method, headers, expiresAt }
PUT  <uploadUrl>                      → kirim byte file + headers yang diminta
POST /api/v1/payment-proofs/{proofId}/complete → server verifikasi ukuran/tipe/checksum
```

Batas: `app.storage.presign-max-bytes` (default 50 MB), TTL
`app.storage.presigned-ttl-seconds` (default 300 detik). Metadata berstatus
`PENDING` sampai `complete` berhasil; PENDING yang kedaluwarsa dibersihkan
otomatis oleh job retensi.

## Retensi

`app.storage.retention-days` (default 365) dan `app.storage.retention-cron`
(default `0 0 3 * * *`). Job harian menghapus bukti `UPLOADED` yang melewati
batas simpan beserta objeknya, serta metadata `PENDING` yang kedaluwarsa.
ADMIN dapat memicu manual:

```
POST /api/v1/payment-proofs/retention/run?olderThanDays=365
```
