CREATE TABLE facility_reviews (
 id UUID PRIMARY KEY,facility_id UUID NOT NULL REFERENCES facilities(id),owner_id UUID NOT NULL,
 state VARCHAR(30) NOT NULL DEFAULT 'PENDING_APPROVAL',snapshot JSONB NOT NULL,
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),reviewed_at TIMESTAMPTZ,reviewed_by UUID,reason VARCHAR(2000),
 CHECK(state IN('PENDING_APPROVAL','APPROVED','REJECTED','SUPPLEMENT_REQUIRED'))
);
CREATE UNIQUE INDEX idx_pending_facility_review ON facility_reviews(facility_id) WHERE state='PENDING_APPROVAL';
CREATE TABLE facility_documents (
 id UUID PRIMARY KEY,facility_id UUID NOT NULL REFERENCES facilities(id),name VARCHAR(180) NOT NULL,
 object_key VARCHAR(700) NOT NULL UNIQUE,content_type VARCHAR(80) NOT NULL,size_bytes BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE event_outbox(event_id UUID PRIMARY KEY,exchange VARCHAR(120) NOT NULL,routing_key VARCHAR(120) NOT NULL,
 body JSONB NOT NULL,attempts INT NOT NULL DEFAULT 0,published_at TIMESTAMPTZ,next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),last_error VARCHAR(500),created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
CREATE FUNCTION protect_facility_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Facility audit is append-only'; END $$;
CREATE TRIGGER facility_audit_immutable BEFORE UPDATE OR DELETE ON facility_audit FOR EACH ROW EXECUTE FUNCTION protect_facility_audit();
CREATE FUNCTION protect_facility_review_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.snapshot IS DISTINCT FROM OLD.snapshot OR NEW.owner_id<>OLD.owner_id OR NEW.facility_id<>OLD.facility_id
 THEN RAISE EXCEPTION 'Submitted facility snapshot is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER facility_review_snapshot_immutable BEFORE UPDATE ON facility_reviews FOR EACH ROW EXECUTE FUNCTION protect_facility_review_snapshot();
