-- Monotonic password generation copied into refresh tokens. Equality with this value
-- invalidates every token minted before a password mutation without relying on clock precision.
ALTER TABLE users
    ADD COLUMN credential_revision BIGINT NOT NULL DEFAULT 0
        CHECK (credential_revision >= 0);
