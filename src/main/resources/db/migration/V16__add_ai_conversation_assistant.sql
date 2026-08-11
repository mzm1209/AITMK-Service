CREATE TABLE ai_conversation_analysis (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  conversation_id BIGINT NOT NULL, resource_id BIGINT NOT NULL,
  lead_row_id VARCHAR(191), assignee_id VARCHAR(64), trigger_type VARCHAR(16) NOT NULL,
  basis_last_message_id BIGINT NOT NULL, customer_message_count INT NOT NULL,
  status VARCHAR(32) NOT NULL, snapshot_json LONGTEXT NOT NULL, schema_version VARCHAR(32) NOT NULL,
  request_id VARCHAR(191) NOT NULL, created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL, started_at DATETIME(6), completed_at DATETIME(6), error_message VARCHAR(2000),
  UNIQUE KEY uk_ai_analysis_request (request_id),
  KEY idx_ai_analysis_conversation (conversation_id, id),
  KEY idx_ai_analysis_basis (conversation_id, basis_last_message_id, status)
);

CREATE TABLE ai_analysis_module (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  analysis_id BIGINT NOT NULL, module_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL,
  workflow_run_id VARCHAR(191), result_json LONGTEXT, input_hash VARCHAR(64), attempt_count INT NOT NULL DEFAULT 0,
  started_at DATETIME(6), completed_at DATETIME(6), error_code VARCHAR(128), error_message VARCHAR(2000),
  CONSTRAINT fk_ai_module_analysis FOREIGN KEY (analysis_id) REFERENCES ai_conversation_analysis(id),
  UNIQUE KEY uk_ai_analysis_module (analysis_id, module_type)
);

CREATE TABLE ai_action_draft (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  analysis_id BIGINT NOT NULL, module_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL,
  draft_type VARCHAR(32) NOT NULL, payload_json LONGTEXT NOT NULL, status VARCHAR(32) NOT NULL,
  external_row_id VARCHAR(191), idempotency_key VARCHAR(191), confirmed_by VARCHAR(64),
  confirmed_payload_json LONGTEXT, confirmed_at DATETIME(6), error_code VARCHAR(128), error_message VARCHAR(2000),
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_ai_draft_analysis FOREIGN KEY (analysis_id) REFERENCES ai_conversation_analysis(id),
  CONSTRAINT fk_ai_draft_module FOREIGN KEY (module_id) REFERENCES ai_analysis_module(id),
  UNIQUE KEY uk_ai_draft_idempotency (idempotency_key),
  KEY idx_ai_draft_analysis (analysis_id)
);

CREATE TABLE ai_analysis_trigger (
  conversation_id BIGINT NOT NULL PRIMARY KEY, resource_id BIGINT NOT NULL,
  basis_last_message_id BIGINT NOT NULL, scheduled_at DATETIME(6) NOT NULL,
  status VARCHAR(16) NOT NULL, updated_at DATETIME(6) NOT NULL,
  KEY idx_ai_trigger_due (status, scheduled_at)
);
