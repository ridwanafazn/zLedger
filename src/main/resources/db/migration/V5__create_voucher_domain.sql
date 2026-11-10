-- V5__create_voucher_domain.sql
-- Domain voucher: katalog, kuota, dan riwayat redeem per user.

DO $$ BEGIN
    CREATE TYPE voucher_status AS ENUM ('DRAFT', 'ACTIVE', 'INACTIVE', 'EXPIRED');
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS vouchers (
    id              UUID            NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    code            VARCHAR(64)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    description     TEXT,
    points_cost     NUMERIC(19,4)   NOT NULL,
    total_quota     INTEGER         NOT NULL,
    remaining_quota INTEGER         NOT NULL,
    starts_at       TIMESTAMP       NOT NULL,
    ends_at         TIMESTAMP       NOT NULL,
    status          voucher_status  NOT NULL DEFAULT 'DRAFT',
    version         BIGINT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_vouchers_code UNIQUE (code),
    CONSTRAINT ck_vouchers_quota CHECK (remaining_quota >= 0 AND remaining_quota <= total_quota),
    CONSTRAINT ck_vouchers_period CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS idx_vouchers_status ON vouchers (status);
CREATE INDEX IF NOT EXISTS idx_vouchers_period ON vouchers (starts_at, ends_at);

CREATE TABLE IF NOT EXISTS voucher_redemptions (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    voucher_id      UUID        NOT NULL,
    owner_id        VARCHAR(64) NOT NULL,
    transaction_id  UUID        NOT NULL,
    points_spent    NUMERIC(19,4) NOT NULL,
    redeemed_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_voucher_redemptions_voucher
        FOREIGN KEY (voucher_id) REFERENCES vouchers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_voucher_redemptions_transaction
        FOREIGN KEY (transaction_id) REFERENCES journal_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT uk_voucher_redemption_owner UNIQUE (voucher_id, owner_id)
);

CREATE INDEX IF NOT EXISTS idx_voucher_redemptions_owner ON voucher_redemptions (owner_id);
CREATE INDEX IF NOT EXISTS idx_voucher_redemptions_voucher ON voucher_redemptions (voucher_id);

COMMENT ON TABLE vouchers IS 'Katalog voucher flash sale dengan kuota terbatas';
COMMENT ON TABLE voucher_redemptions IS 'Riwayat redeem voucher per user (unik per voucher+owner)';
