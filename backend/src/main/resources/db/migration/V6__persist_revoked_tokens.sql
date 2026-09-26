CREATE TABLE revoked_tokens (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_revoked_tokens_expires_at (expires_at)
);
