-- V32: per-team work-category targets (Costs part 3): the share of a team's effort each work category SHOULD take, in whole percent.
-- ONE current set per team, replaced whole by its PUT (hard delete + insert, no history -- the audit trail names every change); the
-- rule that a non-empty set sums to exactly 100 is service validation inside the locked write transaction, not a constraint
-- (a CHECK cannot span rows). An empty set = no targets configured. `category` is a `metrics.work_category_map.category` label, or the
-- literal 'UNCATEGORIZED' (the "No category" row); a label that is no longer mapped stays stored and is flagged on read.
-- Read at QUERY time over stored figures: no derived row depends on it, so no config_revision bump and no DERIVE.
-- team_id has no ON DELETE rule, like every other teams(id) reference: teams are only ever soft-deleted, so a row of a deleted team is
-- inert (its GET/PUT answer 404) and the FK stays valid; a future hard purge of teams must clear it first.
CREATE TABLE metrics.team_work_category_targets (
    team_id INTEGER NOT NULL REFERENCES teams(id),
    category VARCHAR(100) NOT NULL,
    target_percent INTEGER NOT NULL CHECK (target_percent >= 0 AND target_percent <= 100),
    updated_at BIGINT NOT NULL,
    updated_by_user_id BIGINT NULL REFERENCES users(id),
    PRIMARY KEY (team_id, category)
);
