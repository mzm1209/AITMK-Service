-- Read-only preflight for a legacy MariaDB schema before baselineVersion=1.
SELECT COUNT(*) AS flyway_history_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history';

SELECT table_name, column_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND ((table_name = 'business_resource' AND column_name = 'version')
    OR (table_name = 'conversation' AND column_name IN ('version', 'active_resource_id'))
    OR (table_name = 'chat_message' AND column_name IN ('client_request_id', 'file_name', 'failure_code', 'retry_of_message_id')))
ORDER BY table_name, column_name;

SELECT table_name
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name IN ('conversation_agent_state', 'realtime_event');

SELECT resource_id, COUNT(*) AS active_count, GROUP_CONCAT(id ORDER BY id) AS conversation_ids
FROM conversation WHERE status <> 'CLOSED'
GROUP BY resource_id HAVING COUNT(*) > 1;
