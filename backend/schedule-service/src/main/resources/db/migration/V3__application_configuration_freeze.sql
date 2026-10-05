CREATE TABLE application_configuration_freeze (
 facility_id UUID PRIMARY KEY, application_id UUID NOT NULL UNIQUE, frozen BOOLEAN NOT NULL DEFAULT TRUE
);
