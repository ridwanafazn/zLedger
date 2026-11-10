-- V9__create_payment_proofs.sql
-- Fase J: metadata bukti bayar (blob disimpan di object storage: MinIO/S3/local).
--
-- Byte file TIDAK disimpan di PostgreSQL; tabel ini hanya menyimpan metadata
-- dan pointer object key. Integritas dijaga lewat checksum SHA-256.

CREATE TABLE IF NOT EXISTS payment_proofs (
    id                UUID         NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id    UUID         NOT NULL,
    uploader_id       VARCHAR(64)  NOT NULL,
    object_key        VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    checksum_sha256   VARCHAR(64)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_payment_proofs_object_key UNIQUE (object_key),
    CONSTRAINT uk_payment_proofs_checksum UNIQUE (transaction_id, checksum_sha256),
    CONSTRAINT fk_payment_proofs_transaction
        FOREIGN KEY (transaction_id) REFERENCES journal_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_payment_proofs_size CHECK (size_bytes > 0)
);

CREATE INDEX IF NOT EXISTS idx_payment_proofs_transaction ON payment_proofs (transaction_id);
CREATE INDEX IF NOT EXISTS idx_payment_proofs_uploader ON payment_proofs (uploader_id);
CREATE INDEX IF NOT EXISTS idx_payment_proofs_created_at ON payment_proofs (created_at DESC);

COMMENT ON TABLE payment_proofs IS 'Metadata bukti bayar; byte file berada di object storage (MinIO/S3)';
COMMENT ON COLUMN payment_proofs.object_key IS 'Key objek pada bucket, mis. payment-proofs/2026/11/<uuid>.<ext>';
COMMENT ON COLUMN payment_proofs.checksum_sha256 IS 'SHA-256 isi file; unik per transaksi untuk mencegah upload duplikat';
