-- 群发任务
CREATE TABLE broadcast_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    total_recipients INT NOT NULL DEFAULT 0,
    sent_count INT NOT NULL DEFAULT 0,
    delivered_count INT NOT NULL DEFAULT 0,
    read_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    content TEXT,
    media_id VARCHAR(191),
    media_type VARCHAR(32),
    business_account_id VARCHAR(191),
    created_by VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 群发收件人
CREATE TABLE broadcast_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    customer_phone VARCHAR(32) NOT NULL,
    sent_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    external_message_id VARCHAR(191),
    sent_at DATETIME(6),
    delivered_at DATETIME(6),
    read_at DATETIME(6),
    failed_reason VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_broadcast_recipient_task FOREIGN KEY (task_id) REFERENCES broadcast_task(id),
    INDEX idx_broadcast_recipient_task_status (task_id, sent_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 审计日志
CREATE TABLE audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64),
    operator_role VARCHAR(32),
    old_value VARCHAR(1000),
    new_value VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_aggregate (aggregate_type, aggregate_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
