CREATE TABLE ai_conversation_analysis (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL, resource_id BIGINT NOT NULL,
  lead_row_id VARCHAR(191), assignee_id VARCHAR(64), trigger_type VARCHAR(16) NOT NULL,
  basis_last_message_id BIGINT NOT NULL, customer_message_count INT NOT NULL, status VARCHAR(32) NOT NULL,
  snapshot_json LONGTEXT NOT NULL, schema_version VARCHAR(32) NOT NULL, request_id VARCHAR(191) NOT NULL UNIQUE,
  created_by VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP,
  error_message VARCHAR(2000)
);
CREATE INDEX idx_ai_analysis_conversation ON ai_conversation_analysis(conversation_id,id);
CREATE INDEX idx_ai_analysis_basis ON ai_conversation_analysis(conversation_id,basis_last_message_id,status);
CREATE TABLE ai_analysis_module (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, analysis_id BIGINT NOT NULL, module_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL, workflow_run_id VARCHAR(191), result_json LONGTEXT, input_hash VARCHAR(64),
  attempt_count INT NOT NULL DEFAULT 0, started_at TIMESTAMP, completed_at TIMESTAMP,
  error_code VARCHAR(128), error_message VARCHAR(2000),
  CONSTRAINT fk_ai_module_analysis FOREIGN KEY(analysis_id) REFERENCES ai_conversation_analysis(id),
  CONSTRAINT uk_ai_analysis_module UNIQUE(analysis_id,module_type)
);
CREATE TABLE ai_action_draft (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, analysis_id BIGINT NOT NULL, module_id BIGINT NOT NULL,
  conversation_id BIGINT NOT NULL, draft_type VARCHAR(32) NOT NULL, payload_json LONGTEXT NOT NULL,
  status VARCHAR(32) NOT NULL, external_row_id VARCHAR(191), idempotency_key VARCHAR(191) UNIQUE,
  confirmed_by VARCHAR(64), confirmed_payload_json LONGTEXT, confirmed_at TIMESTAMP,
  error_code VARCHAR(128), error_message VARCHAR(2000), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_ai_draft_analysis FOREIGN KEY(analysis_id) REFERENCES ai_conversation_analysis(id),
  CONSTRAINT fk_ai_draft_module FOREIGN KEY(module_id) REFERENCES ai_analysis_module(id)
);
CREATE INDEX idx_ai_draft_analysis ON ai_action_draft(analysis_id);
CREATE TABLE ai_analysis_trigger (
  conversation_id BIGINT PRIMARY KEY, resource_id BIGINT NOT NULL, basis_last_message_id BIGINT NOT NULL,
  scheduled_at TIMESTAMP NOT NULL, status VARCHAR(16) NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_ai_trigger_due ON ai_analysis_trigger(status,scheduled_at);
