-- Phase 2A: move authoritative role/profile state out of users and harden
-- verification/refresh persistence without rewriting the committed V1 history.

CREATE TABLE user_roles (
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, role),
    CONSTRAINT chk_user_roles_role CHECK (role IN ('CUSTOMER', 'OWNER', 'STAFF', 'ADMIN'))
);

-- Preserve existing development accounts. New registrations are created with
-- no row here; successful verification grants CUSTOMER.
INSERT INTO user_roles (user_id, role)
SELECT id, role
FROM users
WHERE status = 'ACTIVE' OR email_verified = TRUE OR phone_verified = TRUE;

DROP INDEX IF EXISTS idx_users_role;
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users DROP COLUMN role;

CREATE TABLE user_profiles (
    user_id        UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    full_name      VARCHAR(255) NOT NULL,
    avatar_url     VARCHAR(2048),
    date_of_birth  DATE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version        BIGINT      NOT NULL DEFAULT 0
);

INSERT INTO user_profiles (user_id, full_name, avatar_url, created_at, updated_at, version)
SELECT id, full_name, avatar_url, created_at, updated_at, version FROM users;

ALTER TABLE users DROP COLUMN full_name;
ALTER TABLE users DROP COLUMN avatar_url;
ALTER TABLE users ADD COLUMN pending_email VARCHAR(255);
ALTER TABLE users ADD COLUMN pending_phone VARCHAR(20);
CREATE UNIQUE INDEX uq_users_pending_email ON users (LOWER(pending_email)) WHERE pending_email IS NOT NULL;
CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
CREATE UNIQUE INDEX uq_users_pending_phone ON users (pending_phone) WHERE pending_phone IS NOT NULL;

ALTER TABLE users ALTER COLUMN status SET DEFAULT 'PENDING_VERIFICATION';
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_status;
ALTER TABLE users ADD CONSTRAINT chk_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'DEACTIVATED'));

-- Existing one-time values are invalidated before the column becomes a hash store.
ALTER TABLE otp_codes RENAME COLUMN code TO code_hash;
ALTER TABLE otp_codes ALTER COLUMN code_hash TYPE VARCHAR(255);
ALTER TABLE otp_codes ADD COLUMN token_hash VARCHAR(64);
UPDATE otp_codes
SET code_hash = 'invalidated:' || id::text,
    verified = TRUE
WHERE TRUE;
ALTER TABLE otp_codes ADD COLUMN verified_at TIMESTAMPTZ;
ALTER TABLE otp_codes DROP CONSTRAINT IF EXISTS chk_otp_purpose;
ALTER TABLE otp_codes ADD CONSTRAINT chk_otp_purpose
    CHECK (purpose IN ('EMAIL_VERIFY', 'EMAIL_CHANGE', 'PHONE_CHANGE', 'PASSWORD_RESET'));
CREATE INDEX idx_otp_codes_lookup ON otp_codes(user_id, purpose, verified, expires_at);

ALTER TABLE refresh_tokens ADD COLUMN rotated_from_id UUID REFERENCES refresh_tokens(id);
ALTER TABLE refresh_tokens ADD COLUMN last_used_at TIMESTAMPTZ;
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens(user_id, revoked, expires_at);
