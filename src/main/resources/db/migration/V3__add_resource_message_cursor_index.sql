CREATE INDEX idx_chat_message_resource_cursor
    ON chat_message (resource_id, created_at, id);
