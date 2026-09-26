-- Per-user feature flags (Lettuce's V46 / Toadie's V12, ported): the DISABLED set — no row = feature
-- enabled, so the empty table needs no backfill and every existing/new user starts with
-- full access. No CHECK on feature: the application enum is the whitelist, so a future
-- feature needs no migration (the V1 role-CHECK is the deliberate exception, not the rule).
-- The feature index backs the users-list feature/featureEnabled filter pair.
CREATE TABLE user_disabled_features (
    user_id BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    feature VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, feature)
);
CREATE INDEX idx_user_disabled_features_feature ON user_disabled_features(feature);

-- MFA joins the per-user feature flags above with an INVERTED default: opt-in, so every
-- existing user (the V3 seed admin, soft-deleted rows included) starts with the disabled
-- row present. UserService.create inserts the same row for every user created after this
-- migration.
INSERT INTO user_disabled_features (user_id, feature)
SELECT id, 'MFA' FROM users
ON CONFLICT DO NOTHING;
