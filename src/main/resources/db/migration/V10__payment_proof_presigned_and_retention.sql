-- V10__payment_proof_presigned_and_retention.sql
-- Dukungan presigned upload (file besar) + retensi otomatis bukti bayar.
--
-- status:
--   PENDING  -> metadata dibuat, objek belum/tengah diunggah langsung ke storage
--   UPLOADED -> objek terverifikasi (ukuran, tipe, checksum) dan siap dipakai
-- upload_method: MULTIPART (lewat API) atau PRESIGNED (langsung ke S3)

ALTER TABLE payment_proofs
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'UPLOADED';

ALTER TABLE payment_proofs
    ADD COLUMN IF NOT EXISTS upload_method VARCHAR(16) NOT NULL DEFAULT 'MULTIPART';

ALTER TABLE payment_proofs
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP;

-- Checksum baru diketahui setelah objek benar-benar ada (presigned upload).
ALTER TABLE payment_proofs
    ALTER COLUMN checksum_sha256 DROP NOT NULL;

DO $$ BEGIN
    ALTER TABLE payment_proofs
        ADD CONSTRAINT ck_payment_proofs_status CHECK (status IN ('PENDING', 'UPLOADED'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE payment_proofs
        ADD CONSTRAINT ck_payment_proofs_upload_method CHECK (upload_method IN ('MULTIPART', 'PRESIGNED'));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_payment_proofs_status
    ON payment_proofs (status);
CREATE INDEX IF NOT EXISTS idx_payment_proofs_expires_at
    ON payment_proofs (expires_at);
CREATE INDEX IF NOT EXISTS idx_payment_proofs_status_created_at
    ON payment_proofs (status, created_at);

COMMENT ON COLUMN payment_proofs.status IS 'PENDING = menunggu unggahan presigned; UPLOADED = objek terverifikasi';
COMMENT ON COLUMN payment_proofs.upload_method IS 'MULTIPART (lewat API) atau PRESIGNED (langsung ke S3)';
COMMENT ON COLUMN payment_proofs.expires_at IS 'Batas waktu unggahan presigned; batch pembersih menghapus PENDING yang kedaluwarsa';
