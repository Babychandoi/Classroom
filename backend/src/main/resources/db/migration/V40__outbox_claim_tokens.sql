-- Fence all acknowledgements by the owner of the current claim, even after stale reclaim.
ALTER TABLE outbox_events ADD COLUMN claim_token CHAR(36) NULL, ALGORITHM=INSTANT;
