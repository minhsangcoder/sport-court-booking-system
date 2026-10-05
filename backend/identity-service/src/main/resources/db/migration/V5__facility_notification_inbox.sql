CREATE TABLE event_inbox(consumer VARCHAR(80) NOT NULL,event_id UUID NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(consumer,event_id));
ALTER TABLE identity_notifications ADD COLUMN source_key VARCHAR(180);
CREATE UNIQUE INDEX idx_identity_notice_source ON identity_notifications(source_key) WHERE source_key IS NOT NULL;
