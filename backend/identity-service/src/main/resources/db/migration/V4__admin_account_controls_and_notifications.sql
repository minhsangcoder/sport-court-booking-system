ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
CREATE TABLE identity_notifications (
 id UUID PRIMARY KEY,user_id UUID NOT NULL REFERENCES users(id),recipient VARCHAR(255) NOT NULL,
 subject VARCHAR(180) NOT NULL,body TEXT NOT NULL,state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),last_error VARCHAR(500),sent_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE FUNCTION protect_identity_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Identity audit records are append-only'; END $$;
CREATE TRIGGER identity_audit_immutable BEFORE UPDATE OR DELETE ON audit_log FOR EACH ROW EXECUTE FUNCTION protect_identity_audit();
