# ADR-005: Blob Storage — MinIO / S3-compatible

- **Status:** Accepted
- **Tanggal:** 2026-10-29
- **Konteks:** Fitur voucher (banner), merchant (logo), dan receipt butuh penyimpanan file.

## Keputusan
MinIO untuk lokal/dev, S3-compatible (GCS interop / AWS S3 / R2) untuk produksi, di balik antarmuka `BlobStorage`.

## Alasan
- S3 API = standar industri & portable.
- Jalan offline, biaya lokal nol, nilai pembelajaran tinggi.
- Cloudinary ditolak karena proprietary lock-in.

## Konsekuensi
- Menambah service MinIO di docker-compose + endpoint `/upload/*` & `/files/*`.
- Byte file tidak melewati aplikasi (presigned upload langsung).
- Lihat detail: `docs/BLOB_STORAGE_DECISION.md`.
