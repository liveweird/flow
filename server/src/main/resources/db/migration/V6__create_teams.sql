-- Flat teams (Lettuce's V2 minus manager_id — Flow has no management chain by design):
-- a team is a name plus its members, the ownership/membership unit future features scope to.
CREATE TABLE teams (
    id SERIAL PRIMARY KEY,
    "name" VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Name uniqueness among ACTIVE teams only, case-insensitively (the soft-delete convention:
-- a deleted team frees its name; "Payments" next to "payments" would be a confusing twin).
CREATE UNIQUE INDEX uq_teams_name_active ON teams (LOWER("name")) WHERE NOT marked_as_deleted;

-- Membership is a pure join (a hard-delete table, the user_disabled_features class): no
-- history worth keeping, wholesale add/remove per row. A soft-deleted user keeps the row —
-- reads join users and mark the member deleted rather than silently dropping it — and a
-- soft-deleted team keeps its roster for the record (reads filter active teams).
CREATE TABLE team_members (
    team_id INTEGER NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (team_id, user_id)
);
CREATE INDEX idx_team_members_user_id ON team_members(user_id);
