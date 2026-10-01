-- Test fixture for FlywayPreflightConfigTest (NOT under db/migration, never applied).
-- An ordinary migration: it does not carry the UTC marker in its header.
ALTER TABLE some_table ADD COLUMN another_column INT NULL;
