-- Test fixture for FlywayPreflightConfigTest (NOT under db/migration, never applied).
-- requires-utc-server: converts columns through a pinned '+00:00' session, so the MySQL server must run in UTC.
ALTER TABLE some_table MODIFY COLUMN some_column DATETIME(6) NOT NULL;
