-- The users table. Conventions carried across the schema:
--   * soft delete via marked_as_deleted — rows are never hard-deleted, and uniqueness is
--     enforced by PARTIAL unique indexes over active rows only, so a deleted user frees
--     their email;
--   * single-column role storage with a CHECK — the wire shape (JWT roles claim) still
--     carries a set of additional roles, so adding a role here never breaks old tokens.
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    "name" VARCHAR(50) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    "role" VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK ("role" IN ('ADMIN', 'USER')),
    -- Epoch millis of the last password change (0 = never); /refresh rejects older tokens.
    password_changed_at BIGINT NOT NULL DEFAULT 0,
    -- Per-user language (Lettuce's V61 / Toadie's V18, folded in): drives the UI at sign-in
    -- and the language of every server-composed email. No CHECK — the application constant
    -- SUPPORTED_LANGUAGES (users/Languages.kt) is the whitelist (the role-CHECK above is the
    -- deliberate exception, not the rule).
    language VARCHAR(10) NOT NULL DEFAULT 'en',
    marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE
);

-- Email uniqueness among ACTIVE accounts only (the soft-delete convention above).
CREATE UNIQUE INDEX uq_users_email_active ON users (email) WHERE NOT marked_as_deleted;
