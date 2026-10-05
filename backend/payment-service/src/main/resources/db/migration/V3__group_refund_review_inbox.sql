CREATE TABLE event_inbox (
 consumer VARCHAR(80) NOT NULL,event_id UUID NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(consumer,event_id)
);
