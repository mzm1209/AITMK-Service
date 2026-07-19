ALTER TABLE ai_daily_report
    MODIFY snapshot_json LONGTEXT NULL,
    MODIFY ai_result_json LONGTEXT NULL;

ALTER TABLE ai_daily_report_conversation
    MODIFY conversation_snapshot_json LONGTEXT NULL,
    MODIFY ai_result_json LONGTEXT NULL;
