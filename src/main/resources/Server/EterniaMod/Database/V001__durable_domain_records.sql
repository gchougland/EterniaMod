CREATE TABLE eternia_records (
    namespace VARCHAR(64) NOT NULL,
    record_key VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    payload BYTEA NOT NULL CHECK (octet_length(payload) <= 8388608),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace, record_key)
);
CREATE INDEX eternia_records_updated ON eternia_records (updated_at);
