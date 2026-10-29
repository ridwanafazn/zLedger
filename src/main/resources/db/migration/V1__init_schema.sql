-- V1__init_schema.sql
-- Initial schema for FinLedger Engine
-- Generated from JPA entities (Account, JournalTransaction, LedgerEntry)

-- ============================================================================
-- EXTENSIONS
-- ============================================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================================================
-- ENUM TYPES
-- ============================================================================
CREATE TYPE account_type AS ENUM ('ASSET', 'LIABILITY', 'REVENUE', 'EXPENSE');
CREATE TYPE currency AS ENUM ('IDR', 'A_POIN');
CREATE TYPE entry_type AS ENUM ('DEBIT', 'CREDIT');
CREATE TYPE reference_type AS ENUM ('PAYMENT', 'REDEMPTION', 'REFUND', 'REWARD');

-- ============================================================================
-- TABLE: accounts
-- ============================================================================
CREATE TABLE accounts (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    owner_id        VARCHAR(64) NOT NULL,
    account_name    VARCHAR(128) NOT NULL,
    account_type    account_type NOT NULL,
    currency        currency    NOT NULL,
    balance         NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_owner_currency UNIQUE (owner_id, currency)
);

CREATE INDEX idx_accounts_owner_id ON accounts (owner_id);
CREATE INDEX idx_accounts_currency ON accounts (currency);
CREATE INDEX idx_accounts_account_type ON accounts (account_type);

-- ============================================================================
-- TABLE: journal_transactions
-- ============================================================================
CREATE TABLE journal_transactions (
    id                  UUID        NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    idempotency_key     VARCHAR(255) NOT NULL,
    reference_type      reference_type NOT NULL,
    description         TEXT,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_journal_transactions_idempotency_key ON journal_transactions (idempotency_key);
CREATE INDEX idx_journal_transactions_reference_type ON journal_transactions (reference_type);
CREATE INDEX idx_journal_transactions_created_at ON journal_transactions (created_at);

-- ============================================================================
-- TABLE: ledger_entries
-- ============================================================================
CREATE TABLE ledger_entries (
    id                  UUID        NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    transaction_id      UUID        NOT NULL,
    account_id          UUID        NOT NULL,
    entry_type          entry_type  NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES journal_transactions (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_entry_type ON ledger_entries (entry_type);
CREATE INDEX idx_ledger_entries_created_at ON ledger_entries (created_at);

-- ============================================================================
-- TRIGGER: updated_at auto-update
-- ============================================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_accounts_updated_at
    BEFORE UPDATE ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================================================
-- COMMENTS (optional, for documentation)
-- ============================================================================
COMMENT ON TABLE accounts IS 'Akaun dompel / ledger account (pelanggan, merchant, system pool)';
COMMENT ON TABLE journal_transactions IS 'Header transaksi jurnal (double-entry)';
COMMENT ON TABLE ledger_entries IS 'Baris entri ledger (DEBIT/CREDIT) per transaksi';
COMMENT ON COLUMN accounts.balance IS 'Balans terkini (cached), disinkronkan via ledger entries';
COMMENT ON COLUMN accounts.version IS 'Optimistic lock version (JPA @Version)';
COMMENT ON COLUMN journal_transactions.idempotency_key IS 'Kunci idempotency dari client (X-Idempotency-Key)';
COMMENT ON COLUMN ledger_entries.amount IS 'Jumlah dengan presisi 4 desimal (NUMERIC 19,4)';