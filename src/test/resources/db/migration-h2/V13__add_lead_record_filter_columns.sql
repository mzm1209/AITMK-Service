ALTER TABLE lead_records
    ADD COLUMN leads_type VARCHAR(128);

ALTER TABLE lead_records
    ADD COLUMN leads_status VARCHAR(128);

CREATE INDEX idx_lead_records_filter_type_status_phone
    ON lead_records (leads_type, leads_status, customer_phone);

CREATE INDEX idx_lead_records_filter_status_phone
    ON lead_records (leads_status, customer_phone);
