CREATE TABLE facility_transfer_policies (
 facility_id UUID PRIMARY KEY REFERENCES facilities(id),enabled BOOLEAN NOT NULL,
 min_lead_seconds INT NOT NULL CHECK(min_lead_seconds>=0 AND min_lead_seconds<=7776000),
 updated_by UUID NOT NULL,updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
