-- ═══════════════════════════════════════════════════════════════
-- SportHub Identity Service — V1 Initial Schema
-- Flyway Migration: V1__init_identity_schema.sql
-- ═══════════════════════════════════════════════════════════════

-- ── UUID Extension ──────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Users Table ─────────────────────────────────────────────────
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           VARCHAR(255) NOT NULL UNIQUE,
    phone           VARCHAR(20)  UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    avatar_url      VARCHAR(500),
    role            VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER',
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at   TIMESTAMPTZ,
    locked_at       TIMESTAMPTZ,
    lock_reason     VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_users_role   CHECK (role IN ('CUSTOMER', 'OWNER', 'STAFF', 'ADMIN')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'PENDING_VERIFICATION', 'DEACTIVATED'))
);

CREATE INDEX idx_users_email  ON users(email);
CREATE INDEX idx_users_phone  ON users(phone);
CREATE INDEX idx_users_role   ON users(role);
CREATE INDEX idx_users_status ON users(status);

-- ── Refresh Tokens ──────────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    device_info     VARCHAR(500),
    ip_address      VARCHAR(45),
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked         BOOLEAN      NOT NULL DEFAULT FALSE,
    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens(expires_at);

-- ── OTP Codes ───────────────────────────────────────────────────
CREATE TABLE otp_codes (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            VARCHAR(6)   NOT NULL,
    purpose         VARCHAR(30)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    max_attempts    INT          NOT NULL DEFAULT 5,
    expires_at      TIMESTAMPTZ  NOT NULL,
    verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_otp_purpose CHECK (purpose IN ('EMAIL_VERIFY', 'PHONE_VERIFY', 'PASSWORD_RESET', 'LOGIN'))
);

CREATE INDEX idx_otp_codes_user_id ON otp_codes(user_id);
CREATE INDEX idx_otp_codes_expires ON otp_codes(expires_at);

-- ── Owner Profiles ──────────────────────────────────────────────
CREATE TABLE owner_profiles (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID         NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    business_name       VARCHAR(255) NOT NULL,
    business_license    VARCHAR(255),
    tax_code            VARCHAR(50),
    bank_account_number VARCHAR(50),
    bank_name           VARCHAR(100),
    bank_branch         VARCHAR(200),
    approval_status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,
    rejection_reason    VARCHAR(500),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT chk_owner_approval CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'))
);

CREATE INDEX idx_owner_profiles_user_id ON owner_profiles(user_id);
CREATE INDEX idx_owner_profiles_status  ON owner_profiles(approval_status);

-- ── Staff Facility Bindings ─────────────────────────────────────
CREATE TABLE staff_facility_bindings (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    staff_user_id   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    facility_id     UUID         NOT NULL,  -- Cross-service reference (Facility Service)
    assigned_by     UUID         NOT NULL REFERENCES users(id),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_staff_facility UNIQUE (staff_user_id, facility_id)
);

CREATE INDEX idx_staff_bindings_staff    ON staff_facility_bindings(staff_user_id);
CREATE INDEX idx_staff_bindings_facility ON staff_facility_bindings(facility_id);

-- ── Staff Permissions ───────────────────────────────────────────
CREATE TABLE staff_permissions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    binding_id      UUID         NOT NULL REFERENCES staff_facility_bindings(id) ON DELETE CASCADE,
    permission      VARCHAR(50)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_binding_permission UNIQUE (binding_id, permission)
);

CREATE INDEX idx_staff_permissions_binding ON staff_permissions(binding_id);

-- ── Audit Log ───────────────────────────────────────────────────
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID,
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(100) NOT NULL,
    entity_id       UUID,
    old_value       JSONB,
    new_value       JSONB,
    ip_address      VARCHAR(45),
    user_agent      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_user_id    ON audit_log(user_id);
CREATE INDEX idx_audit_log_entity     ON audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
