CREATE TABLE agent_accounts (
    row_id        VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT '坐席 CRM rowId',
    login_account VARCHAR(191) NOT NULL              COMMENT '坐席登录名（人类可读）',
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本地坐席账号缓存，登录时同步自 CRM';
