-- Every accepted create key must keep its result even after the payment becomes final.
CREATE TABLE payment_requests (
 payer_id UUID NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 booking_id UUID NOT NULL, member_id UUID, payment_id UUID NOT NULL REFERENCES payment_orders(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(payer_id,idempotency_key)
);
INSERT INTO payment_requests(payer_id,idempotency_key,booking_id,member_id,payment_id)
 SELECT payer_id,idempotency_key,booking_id,member_id,id FROM payment_orders;
