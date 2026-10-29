CREATE TABLE counter_cash_receipts (
 original_payment_id UUID PRIMARY KEY REFERENCES payment_orders(id),
 payment_id UUID NOT NULL UNIQUE REFERENCES payment_orders(id),
 actor_id UUID NOT NULL,
 payload JSONB NOT NULL,
 event_id UUID NOT NULL REFERENCES event_outbox(event_id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_counter_cash_receipt BEFORE UPDATE OR DELETE ON counter_cash_receipts
 FOR EACH ROW EXECUTE FUNCTION protect_payment_evidence();
