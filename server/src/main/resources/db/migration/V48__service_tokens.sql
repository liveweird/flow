-- Service tokens (1.57.0): machine identities for the read-only /api/v1/integration reads (Astrolabe's
-- scheduled sync). Deliberately NOT rows in `users` (a user row leaks into pickers, lists and the
-- last-admin rule). One immutable bearer per row, shown once; only its SHA-256 hex digest is stored
-- (256 bits of server randomness need no bcrypt). `revoked_at` IS the removal (terminal, no UPDATE/DELETE
-- endpoint); revoked rows stay listed for the trail. Neither soft- nor hard-delete: persistence.md
-- "Soft delete (convention)" lists it as the integration-clients exception (Lettuce V71, Toadie V36).
CREATE TABLE service_tokens (
    id           SERIAL       PRIMARY KEY,          -- the id style of V6 teams (INTEGER; UIntIdTable, tokenId: UInt)
    name         VARCHAR(100) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,             -- SHA-256 hex of the full bearer value (prefix included)
    created_by   BIGINT       NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at   BIGINT       NOT NULL,
    expires_at   BIGINT       NULL,                 -- null = no expiry
    last_used_at BIGINT       NULL,                 -- stamped on every authenticated call
    revoked_at   BIGINT       NULL,                 -- non-null = terminally disabled
    revoked_by   BIGINT       NULL REFERENCES users(id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_service_tokens_token_hash ON service_tokens (token_hash);        -- the auth lookup
CREATE UNIQUE INDEX uq_service_tokens_name_live  ON service_tokens (LOWER(name)) WHERE revoked_at IS NULL;  -- two live "Astrolabe" tokens would be confusing; the teams' uq_teams_name_active idiom -> central 23505 -> 409
