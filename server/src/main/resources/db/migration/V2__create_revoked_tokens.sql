-- JWT blocklist for POST /api/v1/logout: revoked token ids (jti) kept until their natural
-- expiry — the revoke path prunes expired rows opportunistically, so the table stays tiny.
CREATE TABLE revoked_tokens (
    jti VARCHAR(36) PRIMARY KEY,
    expires_at BIGINT NOT NULL
);

CREATE INDEX idx_revoked_tokens_expires_at ON revoked_tokens (expires_at);
