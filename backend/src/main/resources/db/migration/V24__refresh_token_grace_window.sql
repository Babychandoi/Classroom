-- V24__refresh_token_grace_window.sql
-- R9-01: concurrent refresh with the same cookie (two tabs racing the same request) or a staggered
-- refresh (tab B still holds the old cookie when it fires, after tab A already rotated it a moment
-- earlier) must not fork the rotation family or log a legitimate multi-tab user out everywhere.
--
-- rotated_at marks *when* a row was retired by rotation (as opposed to explicit logout/reuse
-- revocation, which only sets revoked_at). replaced_by_hash points at the hash of the token that
-- rotation produced, so a reuse of this exact row within the grace window can mint a new child off
-- that same successor (continuing one chain) instead of nuking the whole family.
ALTER TABLE refresh_tokens
    ADD COLUMN rotated_at TIMESTAMP(6) NULL AFTER revoked_at,
    ADD COLUMN replaced_by_hash CHAR(64) NULL AFTER rotated_at;
