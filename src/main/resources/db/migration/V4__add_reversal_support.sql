-- V4__add_reversal_support.sql
-- Mendukung reversal/refund: transaksi kompensasi menunjuk ke transaksi asal.

ALTER TABLE journal_transactions
    ADD COLUMN IF NOT EXISTS reversal_of_id UUID;

DO $$ BEGIN
    ALTER TABLE journal_transactions
        ADD CONSTRAINT fk_journal_transactions_reversal_of
        FOREIGN KEY (reversal_of_id) REFERENCES journal_transactions (id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_journal_transactions_reversal_of
    ON journal_transactions (reversal_of_id);

CREATE INDEX IF NOT EXISTS idx_journal_transactions_created_at_desc
    ON journal_transactions (created_at DESC);
