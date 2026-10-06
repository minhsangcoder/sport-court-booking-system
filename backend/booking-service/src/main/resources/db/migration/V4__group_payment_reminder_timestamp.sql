-- Acceptance timestamp belongs to the obligation; events/history use the existing stores.
-- Legacy members have never been reminded. No payment/allocation state changes.
ALTER TABLE group_members ADD COLUMN last_reminder_at TIMESTAMPTZ;
