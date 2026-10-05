-- Preserve the initial migration and remove its one-interval restriction.
ALTER TABLE operating_hours DROP CONSTRAINT uq_operating_hours;
CREATE UNIQUE INDEX uq_operating_interval ON operating_hours
 (facility_id, COALESCE(court_id,'00000000-0000-0000-0000-000000000000'::uuid),day_of_week,open_time,close_time) WHERE is_active;
ALTER TABLE pricing_rules ADD COLUMN effective_from DATE NOT NULL DEFAULT CURRENT_DATE;
ALTER TABLE pricing_rules ADD COLUMN effective_to DATE;
ALTER TABLE pricing_rules ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'VND';
ALTER TABLE pricing_rules ADD COLUMN sport_category_id UUID;
ALTER TABLE pricing_rules ADD CONSTRAINT chk_effective_period CHECK(effective_to IS NULL OR effective_to >= effective_from);
-- Old zero-price rows cannot be used for quoting; retain them for history.
UPDATE pricing_rules SET is_active=FALSE WHERE price_per_slot=0;
ALTER TABLE pricing_rules ADD CONSTRAINT chk_positive_active_price CHECK(NOT is_active OR price_per_slot>0);
CREATE TABLE schedule_audit (
 id UUID PRIMARY KEY, facility_id UUID NOT NULL, actor_id UUID NOT NULL,
 action VARCHAR(80) NOT NULL, snapshot JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_schedule_audit ON schedule_audit(facility_id,created_at);
