-- Keep existing channel/session/receipt identities and history; distinguish the provider explicitly.
ALTER TABLE payos_channels ADD COLUMN provider VARCHAR(10) NOT NULL DEFAULT 'PAYOS'
 CHECK(provider IN('PAYOS','SEPAY'));
ALTER TABLE payos_qr_sessions ADD COLUMN qr_image TEXT;
ALTER TABLE payos_qr_sessions ALTER COLUMN description TYPE VARCHAR(25);
ALTER TABLE payos_qr_sessions DROP CONSTRAINT payos_qr_sessions_description_check;
ALTER TABLE payos_qr_sessions ADD CHECK(description ~ '^(SH[A-Z0-9]{7}|SC[0-9]{1,18})$');
CREATE TABLE sepay_webhook_receipts (
 channel_id UUID NOT NULL REFERENCES payos_channels(id), transaction_id BIGINT NOT NULL CHECK(transaction_id>0),
 payment_id UUID REFERENCES payment_orders(id), fingerprint VARCHAR(64) NOT NULL,
 raw_ciphertext TEXT NOT NULL, state VARCHAR(20) NOT NULL CHECK(state IN('APPLIED','IGNORED','REVIEW')),
 reason VARCHAR(100), received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(channel_id,transaction_id)
);
CREATE TRIGGER immutable_sepay_webhook BEFORE UPDATE OR DELETE ON sepay_webhook_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_payment_evidence();
CREATE FUNCTION protect_collection_provider() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.provider IS DISTINCT FROM OLD.provider THEN
 RAISE EXCEPTION 'Collection provider is immutable' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER collection_provider_identity BEFORE UPDATE ON payos_channels
 FOR EACH ROW EXECUTE FUNCTION protect_collection_provider();
