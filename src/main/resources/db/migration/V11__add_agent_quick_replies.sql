CREATE TABLE agent_quick_replies (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    agent_row_id   VARCHAR(64) NOT NULL,
    title          VARCHAR(80) NOT NULL,
    content        TEXT NOT NULL,
    category       VARCHAR(40) NULL,
    sort_order     INT NOT NULL DEFAULT 0,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    deleted_at     DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='坐席个人常用话术';

CREATE INDEX idx_agent_quick_replies_agent_list
    ON agent_quick_replies (agent_row_id, enabled, deleted_at, sort_order, updated_at);
