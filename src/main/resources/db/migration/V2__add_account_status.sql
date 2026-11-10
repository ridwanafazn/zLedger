-- V2__add_account_status.sql
-- Add account_status enum type and status column to accounts table

-- Create enum type if not exists
DO $$ BEGIN
    CREATE TYPE account_status AS ENUM ('ACTIVE', 'SUSPENDED', 'CLOSED');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

-- Add status column to accounts table
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS status account_status NOT NULL DEFAULT 'ACTIVE';

COMMENT ON COLUMN accounts.status IS 'Status akun: ACTIVE, SUSPENDED, CLOSED';