-- V8__create_settlement_and_reconciliation.sql
-- Domain Fase I: batch settlement merchant + run rekonsiliasi.
--
-- Alur settlement:
--   gross   = total CREDIT akun merchant dari jurnal PAYMENT dalam periode
--   refund  = total DEBIT akun merchant dari jurnal REFUND (reversal PAYMENT)
--   fee     = (gross - refund) * fee_percent / 100, skala 4, HALF_UP
--   net     = gross - refund - fee
--   payout  = jurnal SETTLEMENT: DEBIT akun merchant, CREDIT akun clearing sistem

DO $$ BEGIN
    CREATE TYPE settlement_status AS ENUM ('PENDING', 'PAID', 'CANCELLED');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE reconciliation_status AS ENUM ('BALANCED', 'DISCREPANCY');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- ---------------------------------------------------------------------------
-- TABLE: merchant_settlements
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS merchant_settlements (
    id                     UUID                 NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    merchant_id            VARCHAR(64)          NOT NULL,
    currency               currency             NOT NULL,
    period_start           TIMESTAMP            NOT NULL,
    period_end             TIMESTAMP            NOT NULL,
    gross_amount           NUMERIC(19,4)        NOT NULL DEFAULT 0.0000,
    refund_amount          NUMERIC(19,4)        NOT NULL DEFAULT 0.0000,
    fee_percent            NUMERIC(7,4)         NOT NULL,
    fee_amount             NUMERIC(19,4)        NOT NULL DEFAULT 0.0000,
    net_amount             NUMERIC(19,4)        NOT NULL DEFAULT 0.0000,
    status                 settlement_status    NOT NULL DEFAULT 'PENDING',
    payout_transaction_id  UUID,
    idempotency_key        VARCHAR(255)         NOT NULL,
    notes                  TEXT,
    version                BIGINT               NOT NULL DEFAULT 0,
    created_at             TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP            NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at                TIMESTAMP,
    cancelled_at           TIMESTAMP,

    CONSTRAINT uk_merchant_settlements_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_merchant_settlements_payout
        FOREIGN KEY (payout_transaction_id) REFERENCES journal_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT ck_merchant_settlements_period CHECK (period_end > period_start),
    CONSTRAINT ck_merchant_settlements_amounts
        CHECK (gross_amount >= 0 AND refund_amount >= 0 AND fee_amount >= 0 AND net_amount >= 0),
    CONSTRAINT ck_merchant_settlements_fee CHECK (fee_percent >= 0 AND fee_percent <= 100)
);

CREATE INDEX IF NOT EXISTS idx_merchant_settlements_merchant
    ON merchant_settlements (merchant_id, currency, period_start DESC);
CREATE INDEX IF NOT EXISTS idx_merchant_settlements_status
    ON merchant_settlements (status);
CREATE INDEX IF NOT EXISTS idx_merchant_settlements_period
    ON merchant_settlements (period_start, period_end);

-- ---------------------------------------------------------------------------
-- TABLE: settlement_items
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS settlement_items (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    settlement_id   UUID            NOT NULL,
    transaction_id  UUID            NOT NULL,
    reference_type  reference_type  NOT NULL,
    entry_type      entry_type      NOT NULL,
    amount          NUMERIC(19,4)   NOT NULL,
    signed_amount   NUMERIC(19,4)   NOT NULL,
    occurred_at     TIMESTAMP       NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_settlement_items_settlement
        FOREIGN KEY (settlement_id) REFERENCES merchant_settlements (id) ON DELETE CASCADE,
    CONSTRAINT fk_settlement_items_transaction
        FOREIGN KEY (transaction_id) REFERENCES journal_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT uk_settlement_items_transaction UNIQUE (settlement_id, transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_settlement_items_settlement ON settlement_items (settlement_id);
CREATE INDEX IF NOT EXISTS idx_settlement_items_transaction ON settlement_items (transaction_id);

-- ---------------------------------------------------------------------------
-- TABLE: reconciliation_runs
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reconciliation_runs (
    id                         UUID                   NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    status                     reconciliation_status  NOT NULL,
    accounts_checked           INTEGER                NOT NULL DEFAULT 0,
    discrepancy_count          INTEGER                NOT NULL DEFAULT 0,
    unbalanced_journal_count   INTEGER                NOT NULL DEFAULT 0,
    total_abs_variance         NUMERIC(19,4)          NOT NULL DEFAULT 0.0000,
    triggered_by               VARCHAR(64),
    notes                      TEXT,
    started_at                 TIMESTAMP              NOT NULL,
    completed_at               TIMESTAMP              NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_runs_completed_at
    ON reconciliation_runs (completed_at DESC);

-- ---------------------------------------------------------------------------
-- TABLE: reconciliation_discrepancies
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS reconciliation_discrepancies (
    id               UUID           NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    run_id           UUID           NOT NULL,
    account_id       UUID,
    discrepancy_type VARCHAR(32)    NOT NULL,
    expected_balance NUMERIC(19,4),
    actual_balance   NUMERIC(19,4),
    variance         NUMERIC(19,4),
    detail           TEXT,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_reconciliation_discrepancies_run
        FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_reconciliation_discrepancies_run
    ON reconciliation_discrepancies (run_id);
CREATE INDEX IF NOT EXISTS idx_reconciliation_discrepancies_account
    ON reconciliation_discrepancies (account_id);

COMMENT ON TABLE merchant_settlements IS 'Batch settlement merchant (gross, refund, fee, net, payout)';
COMMENT ON TABLE settlement_items IS 'Rincian jurnal ledger yang membentuk satu batch settlement';
COMMENT ON TABLE reconciliation_runs IS 'Riwayat eksekusi rekonsiliasi saldo akun vs ledger';
COMMENT ON TABLE reconciliation_discrepancies IS 'Temuan selisih saldo / jurnal tidak seimbang per run';
