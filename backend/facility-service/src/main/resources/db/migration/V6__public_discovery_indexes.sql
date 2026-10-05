CREATE INDEX idx_facility_active_search ON facilities(name,id) WHERE status='ACTIVE';
CREATE INDEX idx_court_enabled_category ON courts(facility_id,sport_category_id) WHERE enabled;
