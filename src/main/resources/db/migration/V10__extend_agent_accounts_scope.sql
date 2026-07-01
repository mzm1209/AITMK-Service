ALTER TABLE agent_accounts
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'TMK' COMMENT '坐席角色：OWNER/MANAGER/TMK';

ALTER TABLE agent_accounts
    ADD COLUMN managed_agent_ids VARCHAR(2048) NULL COMMENT 'MANAGER 管理范围坐席 rowId，逗号分隔';
