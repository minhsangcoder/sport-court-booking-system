-- Durable retry receipt only; no account/legal/file content or credentials.
CREATE TABLE owner_signup_receipts (
 key_hash VARCHAR(64) PRIMARY KEY,
 request_hash VARCHAR(64) NOT NULL,
 user_id UUID NOT NULL UNIQUE REFERENCES users(id),
 application_id UUID NOT NULL UNIQUE REFERENCES owner_applications(id),
 challenge_id UUID NOT NULL REFERENCES otp_codes(id),
 verification_expires_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
