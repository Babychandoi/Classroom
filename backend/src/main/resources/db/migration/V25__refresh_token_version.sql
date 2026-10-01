-- V25__refresh_token_version.sql
-- R11-03: RefreshTokenService.handleReuseOfRetiredToken() re-locks a candidate head row via
-- findByTokenHashForUpdate() inside the SAME persistence context that already loaded it earlier in
-- the same transaction (findCurrentHead()'s own unlocked read, or a previous hop of this same
-- loop). Hibernate's first-level cache returns the already-managed entity instance for a repeat
-- find-by-id-shaped query instead of re-reading the row, so the revokedAt re-check after
-- "re-locking" was effectively checking a value that was never refreshed from the database - only
-- the row lock itself (blocking until the other transaction commits) provided any real protection,
-- and only for as long as nothing else in the same transaction had touched that row first.
--
-- Adding optimistic locking (@Version) is defense in depth alongside the entityManager.detach(...)
-- fix in RefreshTokenService (which detaches each candidate head before its locking re-fetch, so
-- the fetch always performs a genuine read): even if a future code path reintroduces a stale
-- re-check, a concurrent writer bumping the version forces this transaction's own save() to fail
-- fast with an optimistic-locking exception rather than silently overwriting a change it never
-- actually observed.
ALTER TABLE refresh_tokens
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER created_at;
