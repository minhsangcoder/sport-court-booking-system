ALTER TABLE payment_orders ADD COLUMN acquisition_id UUID;
ALTER TABLE payment_orders ADD COLUMN seller_id UUID;
ALTER TABLE payment_requests ADD COLUMN acquisition_id UUID;
DROP INDEX uq_pending_payment;
CREATE UNIQUE INDEX uq_pending_payment ON payment_orders(booking_id,payer_id,purpose,
 COALESCE(member_id,'00000000-0000-0000-0000-000000000000'::uuid),COALESCE(acquisition_id,'00000000-0000-0000-0000-000000000000'::uuid)) WHERE status='PENDING';
ALTER TABLE payment_orders ADD CONSTRAINT valid_transfer_reference CHECK((purpose='TRANSFER' AND acquisition_id IS NOT NULL AND seller_id IS NOT NULL AND member_id IS NULL) OR (purpose<>'TRANSFER' AND acquisition_id IS NULL));
CREATE TABLE transfer_escrow (
 payment_id UUID PRIMARY KEY REFERENCES payment_orders(id),acquisition_id UUID NOT NULL,payer_id UUID NOT NULL,seller_id UUID NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL,
 state VARCHAR(40) NOT NULL CHECK(state IN ('HELD_POLICY_BLOCKED','REFUND_REVIEW')),
 policy_reference VARCHAR(120) NOT NULL DEFAULT 'BLOCKED_RULE:ESCROW_RELEASE',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
