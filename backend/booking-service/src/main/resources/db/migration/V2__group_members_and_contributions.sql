CREATE TABLE booking_groups (
 id UUID PRIMARY KEY REFERENCES bookings(id), owner_id UUID NOT NULL, name VARCHAR(180) NOT NULL,
 state VARCHAR(30) NOT NULL CHECK(state IN ('GROUP_PENDING','CONFIRMED','GROUP_EXPIRED','GROUP_CANCELLED')),
 deadline TIMESTAMPTZ NOT NULL, max_members INT NOT NULL CHECK(max_members BETWEEN 1 AND 50),
 allocations_locked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE group_members (
 id UUID PRIMARY KEY, group_id UUID NOT NULL REFERENCES booking_groups(id), user_id UUID NOT NULL,
 display_name VARCHAR(180) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 amount_due NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(amount_due>=0 AND amount_due=TRUNC(amount_due)),
 amount_paid NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(amount_paid>=0 AND amount_paid<=amount_due),
 payment_state VARCHAR(40) NOT NULL DEFAULT 'UNPAID' CHECK(payment_state IN ('UNPAID','WAITING_FOR_PAYMENT','PAID','PAID_BY_OWNER')),
 payment_requested_at TIMESTAMPTZ, joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(group_id,user_id)
);
CREATE TABLE group_contributions (
 payment_id UUID PRIMARY KEY, group_id UUID NOT NULL REFERENCES booking_groups(id),
 member_id UUID NOT NULL REFERENCES group_members(id), payer_id UUID NOT NULL,
 amount NUMERIC(12,2) NOT NULL CHECK(amount>0), paid_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_group_members_user ON group_members(user_id,group_id) WHERE active;
