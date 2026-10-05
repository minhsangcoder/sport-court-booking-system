CREATE TABLE first_facility_applications (
 application_id UUID PRIMARY KEY, facility_id UUID NOT NULL UNIQUE REFERENCES facilities(id),
 owner_id UUID NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
