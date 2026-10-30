-- The delivery queue also serves as the per-user in-app inbox.
ALTER TABLE identity_notifications ALTER COLUMN recipient DROP NOT NULL;
ALTER TABLE identity_notifications ADD COLUMN read_at TIMESTAMPTZ;
ALTER TABLE identity_notifications ADD COLUMN link_path VARCHAR(300);
CREATE INDEX idx_identity_notifications_inbox ON identity_notifications(user_id,created_at DESC);
