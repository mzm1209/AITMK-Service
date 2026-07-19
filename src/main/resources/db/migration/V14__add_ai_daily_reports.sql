CREATE TABLE ai_daily_report (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    report_date           DATE NOT NULL,
    version               INT NOT NULL DEFAULT 1,
    status                VARCHAR(32) NOT NULL,
    generation_type       VARCHAR(32) NOT NULL,
    scope                 VARCHAR(32) NOT NULL DEFAULT 'all',
    snapshot_json         TEXT NULL,
    ai_result_json        TEXT NULL,
    executive_summary     TEXT NULL,
    risk_level            VARCHAR(32) NULL,
    business_health_score INT NULL,
    dify_run_id           VARCHAR(128) NULL,
    error_message         TEXT NULL,
    created_by            VARCHAR(64) NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    started_at            DATETIME(6) NULL,
    completed_at          DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_daily_report_date_version (report_date, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运营日报';

CREATE INDEX idx_ai_daily_report_list
    ON ai_daily_report (report_date, status, created_at);

CREATE TABLE ai_daily_report_conversation (
    id                         BIGINT NOT NULL AUTO_INCREMENT,
    report_id                  BIGINT NOT NULL,
    conversation_id            BIGINT NULL,
    customer_phone             VARCHAR(32) NULL,
    agent_id                   VARCHAR(64) NULL,
    agent_name                 VARCHAR(128) NULL,
    message_count              INT NULL DEFAULT 0,
    customer_message_count     INT NULL DEFAULT 0,
    agent_message_count        INT NULL DEFAULT 0,
    priority_score             INT NULL DEFAULT 0,
    appointment_status         VARCHAR(64) NULL,
    resolved_status            VARCHAR(64) NULL,
    timeout_count              INT NULL DEFAULT 0,
    conversation_snapshot_json TEXT NULL,
    ai_result_json             TEXT NULL,
    created_at                 DATETIME(6) NOT NULL,
    updated_at                 DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ai_daily_report_conversation_report
        FOREIGN KEY (report_id) REFERENCES ai_daily_report (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 运营日报会话明细';

CREATE INDEX idx_ai_daily_report_conversation_report_priority
    ON ai_daily_report_conversation (report_id, priority_score, id);

CREATE INDEX idx_ai_daily_report_conversation_conversation
    ON ai_daily_report_conversation (conversation_id);
