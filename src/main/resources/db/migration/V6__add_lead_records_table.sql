CREATE TABLE lead_records (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    customer_phone VARCHAR(64) NOT NULL,
    crm_row_id     VARCHAR(64),
    lead_data      LONGTEXT,
    crm_synced_at  DATETIME(6),
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_lead_records_phone UNIQUE (customer_phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_lead_records_crm_row ON lead_records (crm_row_id);
