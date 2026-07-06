CREATE TABLE business_resource (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_phone VARCHAR(32) NOT NULL,
    customer_name VARCHAR(128),
    source_channel VARCHAR(32) NOT NULL DEFAULT 'META',
    source_external_id VARCHAR(191),
    source_campaign_id VARCHAR(191),
    resource_type VARCHAR(32) NOT NULL DEFAULT 'NEW_LEAD',
    resource_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ASSIGNMENT',
    assigned_agent_id VARCHAR(64),
    assigned_at DATETIME(6),
    last_message_at DATETIME(6),
    last_customer_message_at DATETIME(6),
    last_agent_message_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_resource_customer_phone UNIQUE (customer_phone)
);
CREATE INDEX idx_resource_agent_status ON business_resource (assigned_agent_id, resource_status);
CREATE INDEX idx_resource_last_message ON business_resource (last_message_at);
CREATE INDEX idx_resource_source_external ON business_resource (source_channel, source_external_id);

CREATE TABLE conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    customer_phone VARCHAR(32) NOT NULL,
    business_account_id VARCHAR(191),
    channel VARCHAR(32) NOT NULL DEFAULT 'META',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    assigned_agent_id VARCHAR(64),
    ai_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
    first_customer_message_at DATETIME(6),
    first_ai_reply_at DATETIME(6),
    first_agent_reply_at DATETIME(6),
    last_message_at DATETIME(6),
    closed_at DATETIME(6),
    closed_by VARCHAR(64),
    close_reason VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_conversation_resource FOREIGN KEY (resource_id) REFERENCES business_resource (id)
);
CREATE INDEX idx_conversation_resource_status ON conversation (resource_id, status);
CREATE INDEX idx_conversation_customer_status ON conversation (customer_phone, status);
CREATE INDEX idx_conversation_agent_status ON conversation (assigned_agent_id, status);
CREATE INDEX idx_conversation_last_message ON conversation (last_message_at);

CREATE TABLE chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    customer_phone VARCHAR(32) NOT NULL,
    business_account_id VARCHAR(191),
    channel VARCHAR(32) NOT NULL DEFAULT 'META',
    external_message_id VARCHAR(191),
    sender_type VARCHAR(32) NOT NULL,
    sender_id VARCHAR(64),
    operator_role VARCHAR(32),
    message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
    content LONGTEXT,
    media_id VARCHAR(191),
    media_url VARCHAR(1024),
    mime_type VARCHAR(128),
    raw_payload LONGTEXT,
    sent_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    sent_at DATETIME(6),
    delivered_at DATETIME(6),
    read_at DATETIME(6),
    failed_at DATETIME(6),
    failure_reason VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    CONSTRAINT fk_chat_message_resource FOREIGN KEY (resource_id) REFERENCES business_resource (id),
    CONSTRAINT uq_chat_message_external_id UNIQUE (external_message_id)
);
CREATE INDEX idx_chat_message_customer_created ON chat_message (customer_phone, created_at);
CREATE INDEX idx_chat_message_conversation_created ON chat_message (conversation_id, created_at);
CREATE INDEX idx_chat_message_resource_created ON chat_message (resource_id, created_at);
CREATE INDEX idx_chat_message_sender_created ON chat_message (sender_type, created_at);
CREATE INDEX idx_chat_message_status ON chat_message (sent_status, created_at);

CREATE TABLE assignment_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    conversation_id BIGINT,
    customer_phone VARCHAR(32) NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    assigned_by VARCHAR(64) NOT NULL,
    assign_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'SERVING',
    replyable BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6),
    close_reason VARCHAR(255),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    active_resource_id BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'SERVING' THEN resource_id ELSE NULL END),
    PRIMARY KEY (id),
    CONSTRAINT fk_assignment_resource FOREIGN KEY (resource_id) REFERENCES business_resource (id),
    CONSTRAINT fk_assignment_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id),
    CONSTRAINT uq_assignment_active_resource UNIQUE (active_resource_id)
);
CREATE INDEX idx_assignment_agent_status ON assignment_record (agent_id, status);
CREATE INDEX idx_assignment_customer_status ON assignment_record (customer_phone, status);
CREATE INDEX idx_assignment_conversation_status ON assignment_record (conversation_id, status);
CREATE INDEX idx_assignment_resource_assigned ON assignment_record (resource_id, assigned_at);
