-- Capacity management (domain-model A32): one default MD figure per team and one person x day calendar per sprint.
CREATE TABLE metrics.team_capacity_defaults (
    team_id INTEGER PRIMARY KEY REFERENCES teams(id),
    default_capacity_md NUMERIC(8, 2) NULL CHECK (default_capacity_md IS NULL OR default_capacity_md >= 0),
    updated_at BIGINT NOT NULL,
    updated_by_user_id BIGINT NULL REFERENCES users(id)
);

-- The calendar document is read and written whole (the norm.board_columns.status_ids rule), so it is one jsonb:
-- { teamCoefficient, days: [{day, enabled}], people: [{accountId, enabled, coefficient, cells: [{day, availability}]}] }.
CREATE TABLE metrics.sprint_calendars (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    sprint_id BIGINT NOT NULL,
    team_id INTEGER NOT NULL REFERENCES teams(id),
    sprint_start_ms BIGINT NOT NULL,          -- the sprint's start_at at save time: orders "the team's most recent earlier calendar"
    calendar JSONB NOT NULL,
    updated_at BIGINT NOT NULL,
    updated_by_user_id BIGINT NULL REFERENCES users(id),
    PRIMARY KEY (connection_id, sprint_id)
);
CREATE INDEX idx_metrics_sprint_calendars_team_start ON metrics.sprint_calendars (team_id, sprint_start_ms);

-- capacity_source gains CALENDAR and TEAM_DEFAULT ('TEAM_DEFAULT' is 12 chars: widen the VARCHAR(10) first — catalog-only, V18-V20).
-- CONFIGURED stays accepted: rows written before this migration keep it until their connection's next DERIVE rebuilds dim_sprint.
ALTER TABLE metrics.dim_sprint ALTER COLUMN capacity_source TYPE VARCHAR(20);
ALTER TABLE metrics.dim_sprint DROP CONSTRAINT dim_sprint_capacity_source_check;
ALTER TABLE metrics.dim_sprint ADD CONSTRAINT dim_sprint_capacity_source_check
    CHECK (capacity_source IN ('CALENDAR', 'TEAM_DEFAULT', 'DEFAULT', 'CONFIGURED')) NOT VALID;
ALTER TABLE metrics.dim_sprint VALIDATE CONSTRAINT dim_sprint_capacity_source_check;

-- Seed each team's TEAM_DEFAULT from its most recent configured per-sprint figure (ACTIVE teams and connections only; a tie on
-- start_at takes the highest sprint id, a sprint with no start_at ranks last); metrics.team_sprint_capacity stays, unread.
INSERT INTO metrics.team_capacity_defaults (team_id, default_capacity_md, updated_at, updated_by_user_id)
SELECT DISTINCT ON (btm.team_id) btm.team_id, c.capacity_md, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, NULL
FROM metrics.team_sprint_capacity c
JOIN norm.sprints s ON s.connection_id = c.connection_id AND s.sprint_id = c.sprint_id
JOIN metrics.board_team_map btm ON btm.connection_id = s.connection_id AND btm.board_id = s.board_id
JOIN teams t ON t.id = btm.team_id AND NOT t.marked_as_deleted
JOIN public.source_connections sc ON sc.id = c.connection_id AND NOT sc.marked_as_deleted
ORDER BY btm.team_id, s.start_at DESC NULLS LAST, s.sprint_id DESC, s.connection_id ASC;
