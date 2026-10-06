-- UC-2.1 contact information, independent of the Owner's account credential.
-- Existing rows and immutable review snapshots remain unchanged.
ALTER TABLE facilities ADD COLUMN contact_email VARCHAR(254);
