-- ═══════════════════════════════════════════════════════════════
-- SportHub Schedule & Pricing Service — V1 Initial Schema
-- ═══════════════════════════════════════════════════════════════
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ── Court References (local read model, synced from Facility Service) ───
CREATE TABLE court_references (
    court_id        UUID PRIMARY KEY,
    facility_id     UUID         NOT NULL,
    court_name      VARCHAR(100) NOT NULL,
    court_type      VARCHAR(50)  NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    synced_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_court_refs_facility ON court_references(facility_id);

-- ── Operating Hours (Regular weekly schedule) ───────────────────────────
CREATE TABLE operating_hours (
    id                    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    facility_id           UUID         NOT NULL,
    court_id              UUID,  -- NULL = all courts of this facility
    day_of_week           SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    open_time             TIME         NOT NULL,
    close_time            TIME         NOT NULL,
    slot_duration_minutes INT          NOT NULL DEFAULT 60 CHECK (slot_duration_minutes > 0),
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            UUID,
    updated_by            UUID,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_operating_hours UNIQUE (facility_id, court_id, day_of_week),
    CONSTRAINT chk_operating_times CHECK (open_time < close_time)
);
CREATE INDEX idx_operating_hours_facility ON operating_hours(facility_id);

-- ── Exception Calendar (holidays, closures, special hours) ──────────────
CREATE TABLE exception_calendar (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    facility_id       UUID         NOT NULL,
    court_id          UUID,  -- NULL = all courts
    exception_date    DATE         NOT NULL,
    exception_type    VARCHAR(20)  NOT NULL,
    open_time         TIME,  -- for SPECIAL_HOURS
    close_time        TIME,  -- for SPECIAL_HOURS
    reason            VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        UUID,
    updated_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_exception_type CHECK (exception_type IN ('CLOSED', 'SPECIAL_HOURS')),
    CONSTRAINT chk_special_hours CHECK (
        exception_type != 'SPECIAL_HOURS' OR (open_time IS NOT NULL AND close_time IS NOT NULL AND open_time < close_time)
    )
);
CREATE INDEX idx_exception_cal_facility_date ON exception_calendar(facility_id, exception_date);

-- ── Maintenance Windows (blocks slots — highest priority) ───────────────
CREATE TABLE maintenance_windows (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    facility_id     UUID         NOT NULL,
    court_id        UUID         NOT NULL,
    start_datetime  TIMESTAMPTZ  NOT NULL,
    end_datetime    TIMESTAMPTZ  NOT NULL,
    reason          VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_maintenance_times CHECK (start_datetime < end_datetime)
);
CREATE INDEX idx_maintenance_court_time ON maintenance_windows(court_id, start_datetime, end_datetime);

-- ═══════════════════════════════════════════════════════════════
-- Dynamic Pricing Rules
-- Priority cascade (higher = more specific, wins):
--   court_id set     → +100 points
--   court_type set   → +10  points
--   specific_date set → +1   point
--
-- Example priorities:
--   111: court_id + court_type + specific_date (most specific)
--   100: court_id + day_of_week
--   11:  court_type + specific_date
--   10:  court_type + day_of_week
--   1:   facility-wide + specific_date
--   0:   facility-wide + day_of_week (least specific)
-- ═══════════════════════════════════════════════════════════════
CREATE TABLE pricing_rules (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    facility_id     UUID           NOT NULL,
    court_id        UUID,          -- NULL = any court
    court_type      VARCHAR(50),   -- NULL = any court type
    day_of_week     SMALLINT,      -- NULL if specific_date is set
    specific_date   DATE,          -- NULL if day_of_week is set
    start_time      TIME           NOT NULL,
    end_time        TIME           NOT NULL,
    price_per_slot  DECIMAL(12,2)  NOT NULL CHECK (price_per_slot >= 0),
    priority        INT            NOT NULL DEFAULT 0,
    label           VARCHAR(200),  -- e.g. "Weekend peak rate", "Holiday surcharge"
    is_active       BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_pricing_times CHECK (start_time < end_time),
    CONSTRAINT chk_pricing_day_or_date CHECK (day_of_week IS NOT NULL OR specific_date IS NOT NULL)
);
CREATE INDEX idx_pricing_rules_facility ON pricing_rules(facility_id, is_active);
CREATE INDEX idx_pricing_rules_lookup   ON pricing_rules(facility_id, court_id, court_type, specific_date, day_of_week);
