ALTER TABLE facility_documents ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
-- A submitted review keeps its attachments for later inspection, even after a supplement replaces them.
CREATE INDEX idx_current_facility_documents ON facility_documents(facility_id) WHERE NOT archived;
