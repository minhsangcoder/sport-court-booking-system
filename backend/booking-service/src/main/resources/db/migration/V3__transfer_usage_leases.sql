CREATE TABLE transfer_leases (
 listing_id UUID PRIMARY KEY, booking_id UUID NOT NULL REFERENCES bookings(id),seller_id UUID NOT NULL,
 price NUMERIC(12,2) NOT NULL CHECK(price>0),deadline TIMESTAMPTZ NOT NULL,
 state VARCHAR(20) NOT NULL CHECK(state IN ('ACTIVE','LOCKED','COMPLETED','RELEASED')),
 acquisition_id UUID,buyer_id UUID,expires_at TIMESTAMPTZ,payment_id UUID UNIQUE,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX one_live_transfer_per_booking ON transfer_leases(booking_id) WHERE state IN ('ACTIVE','LOCKED');
CREATE TABLE transfer_lease_audit(id UUID PRIMARY KEY,listing_id UUID NOT NULL REFERENCES transfer_leases(listing_id),
 action VARCHAR(40) NOT NULL,details JSONB NOT NULL DEFAULT '{}',created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
