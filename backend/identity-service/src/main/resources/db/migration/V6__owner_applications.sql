CREATE TABLE owner_applications (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id), facility_id UUID NOT NULL UNIQUE,
 state VARCHAR(30) NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','SUBMITTING','PENDING_APPROVAL','SUPPLEMENT_REQUIRED','DECIDING','APPROVING','APPROVED','REJECTED')),
 business_name VARCHAR(180) NOT NULL, facility_name VARCHAR(180) NOT NULL,
 private_payload TEXT NOT NULL, initial_facility JSONB NOT NULL,
 submitted_at TIMESTAMPTZ, reviewed_at TIMESTAMPTZ, reviewed_by UUID REFERENCES users(id),
 reason VARCHAR(1200), decision_action VARCHAR(30), commission_percent NUMERIC(5,2) CHECK(commission_percent BETWEEN 0 AND 100),
 next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), lease_until TIMESTAMPTZ,
 lease_token UUID, last_error VARCHAR(300), created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX owner_one_open_application ON owner_applications(user_id) WHERE state <> 'REJECTED';
CREATE INDEX owner_application_review_queue ON owner_applications(state,submitted_at);
CREATE TABLE owner_application_submissions (
 id UUID PRIMARY KEY, application_id UUID NOT NULL REFERENCES owner_applications(id),
 private_snapshot TEXT NOT NULL, facility_snapshot JSONB NOT NULL,
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TRIGGER owner_submission_immutable BEFORE UPDATE OR DELETE ON owner_application_submissions
 FOR EACH ROW EXECUTE FUNCTION protect_identity_audit();
