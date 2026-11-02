-- Which receiver revision's key authenticated each receipt, so "webhook received" proves the current key.
-- Earlier receipts keep NULL; they stay immutable evidence and are not rewritten.
ALTER TABLE sepay_webhook_receipts ADD COLUMN revision_id UUID REFERENCES payos_channel_revisions(id);
