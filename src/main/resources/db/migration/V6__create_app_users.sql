-- V6__create_app_users.sql
-- User store berbasis database (menggantikan user in-memory dari konfigurasi).

CREATE TABLE IF NOT EXISTS app_users (
    id              UUID         NOT NULL PRIMARY KEY DEFAULT uuid_generate_v4(),
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    email           VARCHAR(255),
    full_name       VARCHAR(128),
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_app_users_username UNIQUE (username),
    CONSTRAINT uk_app_users_email UNIQUE (email),
    -- Username selalu disimpan huruf kecil agar lookup konsisten
    CONSTRAINT ck_app_users_username_format CHECK (username ~ '^[a-z0-9._-]{3,32}$')
);

CREATE TABLE IF NOT EXISTS app_user_roles (
    user_id UUID        NOT NULL,
    role    VARCHAR(32) NOT NULL,

    CONSTRAINT pk_app_user_roles PRIMARY KEY (user_id, role),
    CONSTRAINT fk_app_user_roles_user
        FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE,
    CONSTRAINT ck_app_user_roles_role CHECK (role IN ('ADMIN', 'MERCHANT', 'CUSTOMER'))
);

CREATE INDEX IF NOT EXISTS idx_app_users_enabled ON app_users (enabled);
CREATE INDEX IF NOT EXISTS idx_app_user_roles_role ON app_user_roles (role);

COMMENT ON TABLE app_users IS 'Akun pengguna aplikasi (kredensial + status)';
COMMENT ON TABLE app_user_roles IS 'Role RBAC per pengguna (bisa lebih dari satu)';
COMMENT ON COLUMN app_users.password_hash IS 'BCrypt hash, tidak pernah dikembalikan lewat API';
