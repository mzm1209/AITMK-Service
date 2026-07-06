ALTER TABLE business_resource ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Deterministic repair: preserve the newest active conversation and close older duplicates.
UPDATE conversation c SET status='CLOSED', closed_at=COALESCE(closed_at, CURRENT_TIMESTAMP(6)),
  closed_by='flyway-v2', close_reason='MIGRATION_DUPLICATE_ACTIVE'
WHERE status <> 'CLOSED' AND id NOT IN (
  SELECT keep_id FROM (SELECT MAX(id) keep_id FROM conversation WHERE status <> 'CLOSED' GROUP BY resource_id) x
);
ALTER TABLE conversation ADD COLUMN active_resource_id BIGINT
  GENERATED ALWAYS AS (CASE WHEN status <> 'CLOSED' THEN resource_id ELSE NULL END);
ALTER TABLE conversation ADD CONSTRAINT uq_conversation_active_resource UNIQUE (active_resource_id);

ALTER TABLE chat_message ADD COLUMN client_request_id VARCHAR(191);
ALTER TABLE chat_message ADD COLUMN file_name VARCHAR(255);
ALTER TABLE chat_message ADD COLUMN failure_code VARCHAR(128);
ALTER TABLE chat_message ADD COLUMN retry_of_message_id BIGINT;
ALTER TABLE chat_message ADD CONSTRAINT uq_chat_message_client_request UNIQUE (client_request_id);
ALTER TABLE chat_message ADD CONSTRAINT fk_chat_message_retry FOREIGN KEY (retry_of_message_id) REFERENCES chat_message(id);
CREATE INDEX idx_chat_message_conversation_cursor ON chat_message (conversation_id, created_at, id);

CREATE TABLE conversation_agent_state (
 id BIGINT NOT NULL AUTO_INCREMENT, conversation_id BIGINT NOT NULL, agent_id VARCHAR(64) NOT NULL,
 unread_count BIGINT NOT NULL DEFAULT 0, last_read_message_id BIGINT, last_read_at DATETIME(6),
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL, PRIMARY KEY(id),
 CONSTRAINT uq_conversation_agent_state UNIQUE(conversation_id, agent_id),
 CONSTRAINT fk_cas_conversation FOREIGN KEY(conversation_id) REFERENCES conversation(id),
 CONSTRAINT fk_cas_message FOREIGN KEY(last_read_message_id) REFERENCES chat_message(id)
);
CREATE INDEX idx_cas_agent_unread ON conversation_agent_state(agent_id, unread_count);

CREATE TABLE realtime_event (
 id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(36) NOT NULL, event_type VARCHAR(64) NOT NULL,
 aggregate_type VARCHAR(32) NOT NULL, aggregate_id BIGINT NOT NULL, resource_id BIGINT,
 conversation_id BIGINT, target_agent_id VARCHAR(64) NOT NULL, aggregate_version BIGINT,
 payload_json LONGTEXT NOT NULL, occurred_at DATETIME(6) NOT NULL, published_at DATETIME(6),
 publish_attempts INT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 CONSTRAINT uq_realtime_event_id UNIQUE(event_id)
);
CREATE INDEX idx_realtime_target_recovery ON realtime_event(target_agent_id, id);
CREATE INDEX idx_realtime_unpublished ON realtime_event(published_at, publish_attempts, id);
