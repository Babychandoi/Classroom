-- V35 (R20-06): lease timestamp for the media upload completion state machine.
--
-- MediaService.completeUpload no longer holds a database connection and a row lock across the MinIO stat / copy / magic-byte
-- inspection. It claims the asset in a short transaction (status PENDING -> UPLOADING, completion_claimed_at = now), does the
-- object-store work without a connection, and publishes it (UPLOADING -> UPLOADED) in a second short transaction. The lease
-- lets a later call take over an upload whose request died mid-way; a concurrent duplicate call waits instead of repeating
-- the I/O. status is a VARCHAR(32), so the new UPLOADING value needs no schema change.
ALTER TABLE media_assets
    ADD COLUMN completion_claimed_at DATETIME(6) NULL;
