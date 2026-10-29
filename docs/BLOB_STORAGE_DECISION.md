# Blob Storage Decision — FINAL

> **Status:** FINAL (design lock) · **Last updated:** 2026-10-29

## 1. Keputusan

**Pilih: MinIO (lokal / dev) + S3-compatible (produksi).**

Abstraksi storage-agnostic via antarmuka `BlobStorage`, dengan implementasi:
- **Local/dev/test:** MinIO (container Docker, S3 API).
- **Produksi (opsi):** GCP Cloud Storage (S3-interop) **atau** AWS S3 — tanpa ubah kode domain, cukup config.

Cloudinary **ditolak** (lihat §7).

## 2. Alasan

| Kriteria | MinIO | Cloudinary |
|----------|-------|------------|
| Jalan lokal tanpa internet | ✅ | ❌ |
| S3 API (portable) | ✅ | ❌ proprietary |
| Biaya lokal | Rp0 | butuh akun |
| Image transform | manual | ✅ built-in |
| Nilai portofolio (S3 pattern) | tinggi | rendah |
| Vendor lock-in | rendah | tinggi |

S3 API adalah standar industri; kode yang memakai `software.amazon.awssdk:s3` dapat dipakai lintas penyedia (MinIO, AWS S3, GCS interop, Cloudflare R2).

## 3. Konfigurasi

### 3.1 Docker Compose (tambahan service)
```yaml
  minio:
    image: minio/minio:latest
    container_name: finledger-minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-finledger}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-finledger_minio_secret}
    ports:
      - "9000:9000"   # S3 API
      - "9001:9001"   # Web Console
    volumes:
      - finledger_minio_data:/data
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - finledger-net
```

### 3.2 `application.yml`
```yaml
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 20MB

finledger:
  storage:
    provider: minio           # minio | s3 | gcs
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${STORAGE_ACCESS_KEY:finledger}
    secret-key: ${STORAGE_SECRET_KEY:finledger_minio_secret}
    bucket: ${STORAGE_BUCKET:finledger-assets}
    region: ${STORAGE_REGION:us-east-1}
    presigned-ttl-seconds: 900
    public-base-url: ${STORAGE_PUBLIC_BASE_URL:http://localhost:9000/finledger-assets}
```

### 3.3 `.env.example` (tambahan)
```
MINIO_ROOT_USER=finledger
MINIO_ROOT_PASSWORD=finledger_minio_secret
STORAGE_ENDPOINT=http://localhost:9000
STORAGE_ACCESS_KEY=finledger
STORAGE_SECRET_KEY=finledger_minio_secret
STORAGE_BUCKET=finledger-assets
STORAGE_REGION=us-east-1
STORAGE_PUBLIC_BASE_URL=http://localhost:9000/finledger-assets
```

## 4. Abstraksi Kode

```java
public interface BlobStorage {
    PresignedUpload presignUpload(String key, String contentType, Duration ttl);
    String presignDownload(String key, Duration ttl);
    void delete(String key);
    boolean exists(String key);
    ObjectMetadata stat(String key);
}
```
- `MinioBlobStorage` aktif untuk profil non-prod.
- `S3BlobStorage` aktif untuk profil prod (endpoint GCS/S3).
- Dipilih via `@ConditionalOnProperty(name = "finledger.storage.provider")`.

**Struktur key (namespace):**
```
vouchers/{voucherId}/{uuid}.{ext}
merchants/{merchantId}/logo.{ext}
receipts/{transactionId}/{uuid}.{ext}
```
Sanitasi wajib: tolak `..`, `/` di segmen dinamis, dan path absolut.

## 5. Alur FE (Presigned Upload — Direct-to-Storage)

```
1. FE  → POST /api/v1/ledger/upload/presigned
         { fileName, contentType, folder: "vouchers" }
2. BE  → 200 { uploadUrl, fileKey, publicUrl, expiresInSeconds }
3. FE  → PUT uploadUrl (langsung ke MinIO, body = bytes file)
4. FE  → POST /api/v1/ledger/upload/callback
         { fileKey, entityType: "VOUCHER", entityId: "..." }
5. BE  → verify objek ada (stat), validasi magic bytes, simpan FileMetadata → 200
```
Keuntungan: byte file **tidak** melewati aplikasi → hemat memori/bandwidth, scalable.

## 6. Edge Cases & Penanganan

| # | Kasus | Penanganan |
|---|-------|-----------|
| 1 | FE upload sukses tapi tidak callback | Orphan file → scheduled reaper menghapus blob tanpa metadata setelah 24 jam |
| 2 | Content-type dipalsukan | Validasi magic bytes di callback (bukan hanya header) |
| 3 | Path traversal (`../`) | Sanitasi `fileKey`, whitelist prefix folder |
| 4 | File > 10MB | `413 PAYLOAD_TOO_LARGE` dari presign (validate `contentType`+`size` di presign bila disertakan) |
| 5 | Presigned URL kadaluarsa | FE re-request presign; default TTL 900s |
| 6 | Bucket belum ada saat startup | Bootstrap bucket di `@PostConstruct` (dev) |
| 7 | Hapus entitas (voucher) | Hapus blob terkait + metadata (cascade, best-effort) |
| 8 | Kredensial salah | Fail-fast saat startup (`@PostConstruct` health-check bucket) |

## 7. Alternatif yang Ditolak

- **Cloudinary:** API proprietary → vendor lock-in, tidak bisa jalan offline, nilai pembelajaran S3 rendah. Bila kelak butuh image transform on-the-fly, tambahkan **image service** terpisah (libvips) atau CDN di depan MinIO, bukan ganti storage.

## 8. Dampak ke API

Menambah grup endpoint `/upload/*` dan `/files/*` (lihat `docs/API_SPEC.md` §4.16). Endpoint entitas (voucher, merchant) menerima `imageKey`/`logoKey` hasil callback.

## 9. Perubahan Dokumen
| Versi | Tanggal | Perubahan |
|-------|---------|-----------|
| 1.0 | 2026-10-29 | Keputusan final MinIO + S3-compatible |
