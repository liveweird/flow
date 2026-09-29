-- Derivation corrections from the measure contract (v0.3.0 M3 commit 9d, `.claude/docs/measures.md`,
-- `.claude/docs/domain-model.md` "Amendments" A18/A19/A21/A22). Purely additive — every new column
-- is nullable, no existing V1-V16 file changes, no data migration: the next DERIVE run backfills all
-- of them (there is no `PROCESSING_VERSION`-style gate here, since these are `metrics.*` columns
-- DERIVE rebuilds WHOLESALE every run anyway, never `norm.*`).

-- A19/A22: a domain's project row may have an explicitly configured owner team, feeding
-- `metrics/MetricsDeriver.kt`'s `ownerTeamByDomain` (resolved per DOMAIN key, several project rows
-- may share one; a genuine disagreement among a domain's own configured owners resolves to none,
-- never a fallback; a configured team that is currently soft-deleted also resolves as if
-- unconfigured). `TeamService.delete` never touches this column, the same way it never touches
-- `metrics.board_team_map.team_id` (`.claude/docs/persistence.md` "The `metrics` schema —
-- configuration (V15)"): both are soft-delete-safe by construction — a soft-deleted team's row
-- still exists, so the FK stays valid, and a future metrics-config PUT (or an admin re-pointing the
-- owner) is the normal way to move it off a retired team.
ALTER TABLE metrics.domain_map ADD COLUMN owner_team_id INTEGER NULL REFERENCES teams(id);

-- A21/A22: an open item's team, evaluated NOW rather than at `done_at` (D5 evaluated at the current
-- instant) — the aging-WIP report's own team attribution for IN_PROGRESS items. A22: a CLOSED
-- current sprint is never this column's source (falls back to the assignee), and a currently
-- soft-deleted team never resolves here either.
ALTER TABLE metrics.fact_task_delivery ADD COLUMN current_team_id INTEGER NULL REFERENCES teams(id);
ALTER TABLE metrics.fact_task_delivery ADD COLUMN current_assignee_account_id VARCHAR(100) NULL;
CREATE INDEX idx_metrics_fact_task_delivery_current_team ON metrics.fact_task_delivery (connection_id, current_team_id)
    WHERE done_at IS NULL;

-- A21: the assignee (and their team) at the WORKLOG's own `started_at` — the "assignments half" of
-- foreign-work detection (`author team != assignee team at started_at`, the fallback when the task
-- carries no sprint team at that instant).
ALTER TABLE metrics.fact_worklog ADD COLUMN assignee_account_id_at_started VARCHAR(100) NULL;
ALTER TABLE metrics.fact_worklog ADD COLUMN assignee_team_id_at_started INTEGER NULL REFERENCES teams(id);

-- A19: an epic's own domain's owner team, resolved the same way `metrics.domain_map.owner_team_id`
-- is (configured, else the one mapped board on that project) — stored so report 4/11/14's `owner`
-- team code never has to re-resolve it at query time.
ALTER TABLE metrics.fact_epic_delivery ADD COLUMN owner_team_id INTEGER NULL REFERENCES teams(id);

-- A19/A22: the domain's own resolved owner team (`MetricsDeriver.ownerTeamByDomain`), persisted per
-- domain rather than re-resolved at query time by every reader (report 10's backlog ownership,
-- report 14's "domains without an owner" finding). Written by every DERIVE run alongside the rest
-- of `dim_domain`'s own wholesale rebuild.
ALTER TABLE metrics.dim_domain ADD COLUMN owner_team_id INTEGER NULL REFERENCES teams(id);
