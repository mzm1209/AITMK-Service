CREATE TABLE crm_sync_task (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(64) NOT NULL COMMENT 'CRM 事件类型，如 ADD_CHAT_RECORD / ADD_ASSIGNMENT / UPDATE_LOGIN_STATUS',
    aggregate_type VARCHAR(32) NOT NULL COMMENT '关联的业务实体类型：MESSAGE / RESOURCE / ASSIGNMENT / AGENT',
    aggregate_id BIGINT NOT NULL COMMENT '关联的业务实体 ID',
    payload_json LONGTEXT NOT NULL COMMENT 'CRM 调用参数（序列化的 controls 列表等）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_crm_sync_status (status, retry_count, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
