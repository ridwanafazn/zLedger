-- V7__add_settlement_reference_and_opening_balance.sql
-- Fondasi Fase I (settlement & rekonsiliasi):
--   1. Nilai enum baru SETTLEMENT untuk jurnal payout settlement.
--   2. Kolom accounts.opening_balance agar rekonsiliasi dapat menghitung
--      saldo ekspektasi = opening_balance + CREDIT - DEBIT, termasuk untuk
--      akun yang dibuat dengan saldo awal (seed/admin).
--
-- Catatan: ALTER TYPE ... ADD VALUE tidak boleh memakai nilai baru pada
-- transaksi yang sama. Karena itu nilai SETTLEMENT hanya dipakai mulai
-- migrasi V8 dan kode aplikasi (setelah V7 commit).

ALTER TYPE reference_type ADD VALUE IF NOT EXISTS 'SETTLEMENT';

-- ---------------------------------------------------------------------------
-- accounts.opening_balance
-- ---------------------------------------------------------------------------
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS opening_balance NUMERIC(19,4) NOT NULL DEFAULT 0.0000;

-- Backfill: opening_balance = balance - (total CREDIT - total DEBIT)
-- sehingga akun lama (termasuk yang punya saldo awal tanpa jurnal) tetap konsisten.
-- WAJIB untuk SEMUA akun: akun bersaldo nol yang pernah bertransaksi juga punya
-- baseline bukan nol, jika tidak rekonsiliasi akan melaporkan selisih palsu.
UPDATE accounts a
   SET opening_balance = a.balance - COALESCE((
           SELECT SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END)
             FROM ledger_entries le
            WHERE le.account_id = a.id
       ), 0);

COMMENT ON COLUMN accounts.opening_balance IS
    'Saldo awal akun (baseline rekonsiliasi). Saldo ekspektasi = opening_balance + CREDIT - DEBIT';
