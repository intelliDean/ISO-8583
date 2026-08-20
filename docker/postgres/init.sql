-- PostgreSQL Schema Initializer for ISO 8583 Payment Engine
CREATE TABLE IF NOT EXISTS iso_transactions (
    stan VARCHAR(12) PRIMARY KEY,
    mti VARCHAR(4) NOT NULL,
    masked_pan VARCHAR(19) NOT NULL,
    amount_iso VARCHAR(12) NOT NULL,
    processing_code VARCHAR(6),
    auth_code VARCHAR(6),
    response_code VARCHAR(2),
    state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS iso_clearing_batches (
    batch_id VARCHAR(64) PRIMARY KEY,
    settlement_date VARCHAR(8) NOT NULL,
    network_id VARCHAR(32) NOT NULL,
    total_transactions INT NOT NULL,
    presentment_count INT NOT NULL,
    chargeback_count INT NOT NULL,
    total_gross_amount_iso VARCHAR(16) NOT NULL,
    total_interchange_fee_iso VARCHAR(16) NOT NULL,
    net_settlement_amount_iso VARCHAR(16) NOT NULL,
    raw_batch_file TEXT NOT NULL,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS iso_outbox_events (
    id VARCHAR(64) PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    topic VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    dispatched_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_iso_outbox_pending ON iso_outbox_events (status, created_at);
