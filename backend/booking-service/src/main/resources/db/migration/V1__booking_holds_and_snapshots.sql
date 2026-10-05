CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE TABLE slot_reservations (
 id UUID PRIMARY KEY, court_id UUID NOT NULL, facility_id UUID NOT NULL, holder_id UUID NOT NULL,
 starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('HOLD','BOOKED','RELEASED')),
 quote JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 CHECK(starts_at<ends_at),
 EXCLUDE USING gist(court_id WITH =,tstzrange(starts_at,ends_at,'[)') WITH &&) WHERE(state IN ('HOLD','BOOKED'))
);
CREATE INDEX idx_hold_expiry ON slot_reservations(expires_at) WHERE state='HOLD';
CREATE TABLE bookings (
 id UUID PRIMARY KEY, reservation_id UUID NOT NULL UNIQUE REFERENCES slot_reservations(id),
 facility_id UUID NOT NULL, court_id UUID NOT NULL, created_by UUID NOT NULL, customer_id UUID NOT NULL,
 current_holder_id UUID NOT NULL, starts_at TIMESTAMPTZ NOT NULL, ends_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(30) NOT NULL CHECK(status IN ('PENDING','CONFIRMED','CHECKED_IN','COMPLETED','CANCELLED','EXPIRED')),
 source VARCHAR(30) NOT NULL CHECK(source IN ('ONLINE','OFFLINE_COUNTER')),
 guest_name VARCHAR(180),guest_phone VARCHAR(30),amount NUMERIC(12,2) NOT NULL CHECK(amount>0),currency VARCHAR(3) NOT NULL,
 price_snapshot JSONB NOT NULL, hold_expires_at TIMESTAMPTZ NOT NULL,payment_id UUID,paid_at TIMESTAMPTZ,
 checkin_token_hash VARCHAR(64),checkin_token_version INT NOT NULL DEFAULT 0,
 checked_in_at TIMESTAMPTZ,completed_at TIMESTAMPTZ,version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),CHECK(starts_at<ends_at)
);
CREATE INDEX idx_booking_customer ON bookings(current_holder_id,starts_at DESC);
CREATE INDEX idx_booking_facility ON bookings(facility_id,starts_at);
CREATE TABLE booking_history(id UUID PRIMARY KEY,booking_id UUID NOT NULL REFERENCES bookings(id),actor_id UUID,
 action VARCHAR(60) NOT NULL,details JSONB NOT NULL DEFAULT '{}',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE request_idempotency(actor_id UUID NOT NULL,operation VARCHAR(50) NOT NULL,key VARCHAR(100) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL,resource_id UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(actor_id,operation,key));
CREATE TABLE event_inbox(consumer VARCHAR(80) NOT NULL,event_id UUID NOT NULL,received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),PRIMARY KEY(consumer,event_id));
CREATE TABLE event_outbox(event_id UUID PRIMARY KEY,exchange VARCHAR(120) NOT NULL,routing_key VARCHAR(120) NOT NULL,
 body JSONB NOT NULL,attempts INT NOT NULL DEFAULT 0,published_at TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),last_error VARCHAR(500),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE TABLE payment_reconciliation(id UUID PRIMARY KEY,booking_id UUID NOT NULL REFERENCES bookings(id),payment_id UUID NOT NULL UNIQUE,
 reason VARCHAR(120) NOT NULL,state VARCHAR(40) NOT NULL DEFAULT 'REQUIRES_REVIEW',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
-- Price snapshot may never be changed after creation, including transfers.
CREATE FUNCTION protect_booking_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.price_snapshot IS DISTINCT FROM OLD.price_snapshot OR NEW.amount<>OLD.amount OR NEW.currency<>OLD.currency
 THEN RAISE EXCEPTION 'Booking price snapshot is immutable'; END IF; RETURN NEW;
END $$;
CREATE TRIGGER booking_snapshot_immutable BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION protect_booking_snapshot();
