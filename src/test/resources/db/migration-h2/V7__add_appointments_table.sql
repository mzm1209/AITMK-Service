CREATE TABLE appointments (
    id                BIGINT NOT NULL AUTO_INCREMENT,
    resource_id       BIGINT NOT NULL,
    title             VARCHAR(255) NOT NULL,
    appointment_time  DATETIME(6) NOT NULL,
    duration_minutes  INT NOT NULL DEFAULT 30,
    notes             TEXT,
    status            VARCHAR(32) NOT NULL DEFAULT 'SCHEDULED',
    created_by        VARCHAR(64),
    created_at        DATETIME(6) NOT NULL,
    updated_at        DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_appointment_resource FOREIGN KEY (resource_id) REFERENCES business_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_appointments_resource ON appointments (resource_id, appointment_time);
