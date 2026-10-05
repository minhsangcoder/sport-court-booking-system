CREATE TABLE transfer_listings (
 id UUID PRIMARY KEY,booking_id UUID NOT NULL,seller_id UUID NOT NULL,facility_id UUID NOT NULL,court_id UUID NOT NULL,
 snapshot JSONB NOT NULL,original_amount NUMERIC(12,2) NOT NULL,price NUMERIC(12,2) NOT NULL CHECK(price>0 AND price<=original_amount),currency VARCHAR(3) NOT NULL,
 starts_at TIMESTAMPTZ NOT NULL,ends_at TIMESTAMPTZ NOT NULL,deadline TIMESTAMPTZ NOT NULL CHECK(deadline<=starts_at),
 state VARCHAR(30) NOT NULL CHECK(state IN ('ACTIVE','LOCKED','COMPLETED','WITHDRAWN','EXPIRED','PENDING_AUDIT')),
 workflow VARCHAR(30) NOT NULL CHECK(workflow IN ('REGISTER','READY','EDIT','LOCK','UNLOCK','WITHDRAW','HANDOFF','REVIEW')),
 active_acquisition_id UUID,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),attempts INT NOT NULL DEFAULT 0,last_error VARCHAR(500),
 version BIGINT NOT NULL DEFAULT 0,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX one_active_listing_per_booking ON transfer_listings(booking_id) WHERE state IN ('ACTIVE','LOCKED','PENDING_AUDIT');
CREATE TABLE transfer_acquisitions (
 id UUID PRIMARY KEY,listing_id UUID NOT NULL REFERENCES transfer_listings(id),buyer_id UUID NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL,expires_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(30) NOT NULL CHECK(state IN ('PENDING','PENDING_HANDOFF','SUCCESS','CANCELLED','EXPIRED','FAILED','PENDING_AUDIT')),
 payment_id UUID UNIQUE,payment_payload JSONB,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE request_idempotency(actor_id UUID NOT NULL,operation VARCHAR(40) NOT NULL,key VARCHAR(100) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,resource_id UUID NOT NULL,PRIMARY KEY(actor_id,operation,key));
CREATE TABLE transfer_audit(id UUID PRIMARY KEY,listing_id UUID NOT NULL REFERENCES transfer_listings(id),actor_id UUID,
 action VARCHAR(60) NOT NULL,details JSONB NOT NULL DEFAULT '{}',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE event_inbox(consumer VARCHAR(80) NOT NULL,event_id UUID NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(consumer,event_id));
CREATE TABLE event_outbox(event_id UUID PRIMARY KEY,exchange VARCHAR(120) NOT NULL,routing_key VARCHAR(120) NOT NULL,
 body JSONB NOT NULL,attempts INT NOT NULL DEFAULT 0,published_at TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),last_error VARCHAR(500),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE transfer_reconciliation(id UUID PRIMARY KEY,listing_id UUID NOT NULL REFERENCES transfer_listings(id),payment_id UUID NOT NULL UNIQUE,
 payer_id UUID NOT NULL,reason VARCHAR(120) NOT NULL,state VARCHAR(40) NOT NULL DEFAULT 'REQUIRES_REVIEW',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
