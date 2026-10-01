-- V23__refresh_tokens.sql
-- R8-06: HttpOnly-cookie refresh token flow. Only a one-way hash of the raw refresh token is ever
-- persisted (same pattern as revoked_tokens - see V6), so a database read alone cannot mint a
-- usable session token. family_id groups every token produced by rotating a single login: reusing
-- an already-rotated (or revoked) token revokes the whole family, since that reuse can only happen
-- if the token was stolen and both the legitimate client and the attacker now hold a copy.
CREATE TABLE refresh_tokens (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    family_id VARCHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_refresh_tokens_family (family_id),
    INDEX idx_refresh_tokens_user (user_id),
    INDEX idx_refresh_tokens_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
