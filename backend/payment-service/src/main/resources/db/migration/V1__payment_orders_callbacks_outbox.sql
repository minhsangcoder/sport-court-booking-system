CREATE TABLE payment_orders (
 id UUID PRIMARY KEY,booking_id UUID NOT NULL,payer_id UUID NOT NULL,member_id UUID,purpose VARCHAR(30) NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL,
 provider VARCHAR(40) NOT NULL,provider_reference VARCHAR(120) NOT NULL UNIQUE,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PENDING','SUCCESS','FAILED','EXPIRED')),
 idempotency_key VARCHAR(100) NOT NULL,expires_at TIMESTAMPTZ NOT NULL,paid_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 UNIQUE(payer_id,idempotency_key)
);
CREATE UNIQUE INDEX uq_pending_payment ON payment_orders(booking_id,payer_id,purpose,COALESCE(member_id,'00000000-0000-0000-0000-000000000000'::uuid)) WHERE status='PENDING';
CREATE TABLE provider_callbacks(provider VARCHAR(40) NOT NULL,transaction_id VARCHAR(120) NOT NULL,
 payment_id UUID NOT NULL REFERENCES payment_orders(id),fingerprint VARCHAR(64) NOT NULL,body JSONB NOT NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(provider,transaction_id));
CREATE TABLE payment_audit(id UUID PRIMARY KEY,payment_id UUID NOT NULL REFERENCES payment_orders(id),actor_id UUID,
 action VARCHAR(80) NOT NULL,details JSONB NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE event_outbox(event_id UUID PRIMARY KEY,exchange VARCHAR(120) NOT NULL,routing_key VARCHAR(120) NOT NULL,
 body JSONB NOT NULL,attempts INT NOT NULL DEFAULT 0,published_at TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),last_error VARCHAR(500),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE refunds(id UUID PRIMARY KEY,payment_id UUID NOT NULL REFERENCES payment_orders(id),requester_id UUID NOT NULL,
 reason VARCHAR(500) NOT NULL,state VARCHAR(40) NOT NULL,amount NUMERIC(12,2),policy_reference VARCHAR(120),
 idempotency_key VARCHAR(100) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),UNIQUE(requester_id,idempotency_key));
