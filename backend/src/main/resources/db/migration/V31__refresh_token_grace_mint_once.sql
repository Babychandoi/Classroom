-- V31__refresh_token_grace_mint_once.sql
-- R19-07: the R9-01 grace window lets a token that was retired by rotation mint one more child within
-- grace_window_seconds (a staggered multi-tab refresh). Nothing bounded HOW MANY times: every replay of the
-- same retired token minted yet another session (and, with a 24h access JWT, another day of access).
--
-- grace_minted_at records that this retired row has already produced its single grace re-mint. It is written in
-- the same transaction (and under the same row lock) as the mint, so of any number of concurrent replays exactly
-- one succeeds; a later replay of the same row is refused (401) and, once the short race tolerance has passed,
-- is treated as reuse of a stolen token and revokes the whole family.
ALTER TABLE refresh_tokens
    ADD COLUMN grace_minted_at DATETIME(6) NULL AFTER replaced_by_hash;
