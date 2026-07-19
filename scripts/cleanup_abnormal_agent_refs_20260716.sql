-- Cleanup abnormal agent references found after syncing production data locally.
--
-- Scope:
-- 1) Remove test/fixture conversations for these non-CRM agent IDs:
--    agent-new, manager-1, manager-2, outside-tmk, owner-1, tmk-1, tmk-2
--    Only ds% / rt% customer_phone rows with no chat messages are selected.
-- 2) Repair historical CRM migration rows where an empty relation was stored as literal []:
--    keep conversations/resources/messages, set conversation.assigned_agent_id to NULL,
--    and delete the already CLOSED assignment_record rows for agent_id='[]'.
--
-- Intended database: im
-- Review the SELECT results before COMMIT if running manually.

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS tmp_abnormal_test_conversation_ids;
CREATE TEMPORARY TABLE tmp_abnormal_test_conversation_ids (
    id BIGINT PRIMARY KEY
);

INSERT INTO tmp_abnormal_test_conversation_ids (id)
SELECT c.id
FROM conversation c
WHERE c.assigned_agent_id IN (
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
)
AND (c.customer_phone LIKE 'ds%' OR c.customer_phone LIKE 'rt%')
AND NOT EXISTS (
    SELECT 1
    FROM chat_message m
    WHERE m.conversation_id = c.id
);

DROP TEMPORARY TABLE IF EXISTS tmp_abnormal_test_resource_ids;
CREATE TEMPORARY TABLE tmp_abnormal_test_resource_ids (
    id BIGINT PRIMARY KEY
);

INSERT INTO tmp_abnormal_test_resource_ids (id)
SELECT DISTINCT c.resource_id
FROM conversation c
JOIN tmp_abnormal_test_conversation_ids t ON t.id = c.id;

SELECT 'before_test_conversations' AS metric, COUNT(*) AS value
FROM tmp_abnormal_test_conversation_ids;

SELECT 'before_test_resources' AS metric, COUNT(*) AS value
FROM tmp_abnormal_test_resource_ids;

SELECT 'before_bracket_conversations' AS metric, COUNT(*) AS value
FROM conversation
WHERE assigned_agent_id = '[]' AND status = 'CLOSED';

SELECT 'before_bracket_assignments' AS metric, COUNT(*) AS value
FROM assignment_record
WHERE agent_id = '[]' AND status = 'CLOSED';

DELETE re
FROM realtime_event re
WHERE re.target_agent_id IN (
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

DELETE ar
FROM assignment_record ar
JOIN tmp_abnormal_test_conversation_ids t ON t.id = ar.conversation_id
WHERE ar.agent_id IN (
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

DELETE c
FROM conversation c
JOIN tmp_abnormal_test_conversation_ids t ON t.id = c.id;

DELETE r
FROM business_resource r
JOIN tmp_abnormal_test_resource_ids t ON t.id = r.id
WHERE NOT EXISTS (
    SELECT 1
    FROM conversation c
    WHERE c.resource_id = r.id
)
AND NOT EXISTS (
    SELECT 1
    FROM chat_message m
    WHERE m.resource_id = r.id
);

UPDATE conversation
SET assigned_agent_id = NULL
WHERE assigned_agent_id = '[]'
AND status = 'CLOSED';

DELETE FROM assignment_record
WHERE agent_id = '[]'
AND status = 'CLOSED';

SELECT 'after_abnormal_agent_account_rows' AS metric, COUNT(*) AS value
FROM agent_accounts
WHERE row_id IN (
    '[]',
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

SELECT 'after_abnormal_conversation_refs' AS metric, COUNT(*) AS value
FROM conversation
WHERE assigned_agent_id IN (
    '[]',
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

SELECT 'after_abnormal_assignment_refs' AS metric, COUNT(*) AS value
FROM assignment_record
WHERE agent_id IN (
    '[]',
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

SELECT 'after_abnormal_realtime_refs' AS metric, COUNT(*) AS value
FROM realtime_event
WHERE target_agent_id IN (
    '[]',
    'agent-new',
    'manager-1',
    'manager-2',
    'outside-tmk',
    'owner-1',
    'tmk-1',
    'tmk-2'
);

SELECT 'after_numeric_closed_messages_kept' AS metric, COUNT(*) AS value
FROM chat_message m
JOIN conversation c ON c.id = m.conversation_id
WHERE c.customer_phone REGEXP '^[0-9]+$'
AND c.status = 'CLOSED';

COMMIT;
