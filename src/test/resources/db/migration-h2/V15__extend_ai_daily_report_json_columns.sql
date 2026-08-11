ALTER TABLE ai_daily_report
    ALTER COLUMN snapshot_json LONGTEXT;

ALTER TABLE ai_daily_report
    ALTER COLUMN ai_result_json LONGTEXT;

ALTER TABLE ai_daily_report_conversation
    ALTER COLUMN conversation_snapshot_json LONGTEXT;

ALTER TABLE ai_daily_report_conversation
    ALTER COLUMN ai_result_json LONGTEXT;
