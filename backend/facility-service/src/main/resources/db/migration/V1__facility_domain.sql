CREATE TABLE sport_categories (
 id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE, active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE facilities (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL, name VARCHAR(180) NOT NULL, phone VARCHAR(30) NOT NULL,
 address_line VARCHAR(500) NOT NULL, province VARCHAR(100) NOT NULL, district VARCHAR(100) NOT NULL,
 ward VARCHAR(100) NOT NULL, description TEXT, timezone VARCHAR(80) NOT NULL,
 latitude NUMERIC(10,7), longitude NUMERIC(10,7), status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), version BIGINT NOT NULL DEFAULT 0,
 CHECK(status IN('DRAFT','PENDING_APPROVAL','ACTIVE','SUSPENDED','REJECTED'))
);
CREATE INDEX idx_facility_owner ON facilities(owner_id);
CREATE TABLE facility_amenities (facility_id UUID NOT NULL REFERENCES facilities(id), name VARCHAR(100) NOT NULL,
 PRIMARY KEY(facility_id,name));
CREATE TABLE courts (
 id UUID PRIMARY KEY, facility_id UUID NOT NULL REFERENCES facilities(id), sport_category_id UUID NOT NULL REFERENCES sport_categories(id),
 code VARCHAR(50) NOT NULL, name VARCHAR(180) NOT NULL, description TEXT, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0, UNIQUE(facility_id,code), UNIQUE(facility_id,name)
);
CREATE TABLE maintenance_windows (
 id UUID PRIMARY KEY, court_id UUID NOT NULL REFERENCES courts(id), starts_at TIMESTAMPTZ NOT NULL,
 ends_at TIMESTAMPTZ NOT NULL, reason VARCHAR(1200) NOT NULL, created_by UUID NOT NULL, cancelled BOOLEAN NOT NULL DEFAULT FALSE,
 CHECK(starts_at<ends_at)
);
CREATE INDEX idx_maintenance_court ON maintenance_windows(court_id,starts_at,ends_at);
CREATE TABLE facility_images (
 id UUID PRIMARY KEY, facility_id UUID NOT NULL REFERENCES facilities(id), court_id UUID REFERENCES courts(id),
 object_key VARCHAR(700) NOT NULL UNIQUE, content_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE facility_audit (
 id UUID PRIMARY KEY, actor_id UUID NOT NULL, facility_id UUID NOT NULL REFERENCES facilities(id),
 action VARCHAR(100) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), details TEXT
);
