CREATE TABLE privacy_requests (
    user_id VARCHAR(36) PRIMARY KEY,
    id VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(24) NOT NULL,
    reason VARCHAR(2000) NULL,
    resolution VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    resolved_by VARCHAR(36) NULL,
    CONSTRAINT fk_privacy_request_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_privacy_request_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
