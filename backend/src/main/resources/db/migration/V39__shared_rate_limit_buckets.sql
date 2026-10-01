CREATE TABLE rate_limit_buckets (
    bucket_key CHAR(64) NOT NULL PRIMARY KEY,
    hits BIGINT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    blocked_until DATETIME(6) NULL,
    INDEX idx_rate_limit_expiry (expires_at)
) ENGINE=InnoDB;
