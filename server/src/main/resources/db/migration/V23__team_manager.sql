-- A team's optional manager — a Flow login (users.id is BIGSERIAL, hence BIGINT): only the manager or an
-- ADMIN edits the team's capacity (V24). At most one per team, one user may manage several teams. Lettuce's
-- V2 `teams.manager_id` returns NULLABLE and chain-less (no management chain, no succession history). A
-- soft-deleted user's id may linger here: readers resolve the manager through ACTIVE users and read a
-- deleted one as "no manager" — the `metrics.domain_map.owner_team_id` idiom (soft-delete-safe by construction).
ALTER TABLE teams ADD COLUMN manager_user_id BIGINT NULL REFERENCES users(id);
CREATE INDEX idx_teams_manager_user_id ON teams(manager_user_id);
