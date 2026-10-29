-- A booking may now be one 30-minute step or longer; there is no upper duration limit.
-- It still has to fit one day and one continuous opening window.
ALTER TABLE facility_booking_policies DROP CONSTRAINT facility_booking_policies_minimum_minutes_check;
ALTER TABLE facility_booking_policies DROP CONSTRAINT facility_booking_policies_maximum_minutes_check;
ALTER TABLE facility_booking_policies ALTER COLUMN maximum_minutes DROP NOT NULL;
ALTER TABLE facility_booking_policies ALTER COLUMN maximum_minutes DROP DEFAULT;
ALTER TABLE facility_booking_policies ALTER COLUMN minimum_minutes SET DEFAULT 30;
UPDATE facility_booking_policies SET minimum_minutes = 30, maximum_minutes = NULL;
ALTER TABLE facility_booking_policies ADD CONSTRAINT facility_booking_policies_minimum_minutes_check
  CHECK (minimum_minutes = 30);
ALTER TABLE facility_booking_policies ADD CONSTRAINT facility_booking_policies_maximum_minutes_check
  CHECK (maximum_minutes IS NULL);
