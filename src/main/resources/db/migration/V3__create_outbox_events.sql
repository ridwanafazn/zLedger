-- V3__create_outbox_events.sql
-- Transactional Outbox: event Kafka ditulis di transaksi yang sama dengan ledger,
-- lalu dikirim oleh relay terjadwal. Menjamin tidak ada event hilang bila Kafka down.

DO $$ BEGIN
    CREATE TYPE outbox_status AS ENUM ('PENDING', 'PUBLISHED', 'FAILED');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS outbox_events (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         TEXT            NOT NULL,
    status          outbox_status   NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER         NOT NULL DEFAULT 0,
    last_error      VARCHAR(500),
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events (status, created_at);
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_events IS 'Outbox pattern: event domain yang harus dipublikasikan ke Kafka';
