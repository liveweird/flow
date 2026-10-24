-- Single-use password-reset grants (2D3): only the SHA-256 digest of the emailed token is stored.
-- `email` and `credential_revision` are the account snapshot the link was issued against — a
-- grant goes stale when either moves. Hard-delete table (persistence.md "Soft delete (convention)").
CREATE TABLE password_reset_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL,
    credential_revision BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);
CREATE INDEX idx_password_reset_tokens_expires_at ON password_reset_tokens (expires_at);
