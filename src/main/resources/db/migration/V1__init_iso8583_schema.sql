-- =============================================================================
-- ISO 8583 Enterprise Payment Engine — Database Schema (PostgreSQL)
-- Version: V1.0.0
-- =============================================================================

-- 1. Transactions Ledger
CREATE TABLE IF NOT EXISTS iso_transactions (
    id BIGSERIAL PRIMARY KEY,
    stan VARCHAR(12) NOT NULL,
    masked_pan VARCHAR(32) NOT NULL,
    processing_code VARCHAR(6),
    authorised_amount VARCHAR(12) NOT NULL,
    reversed_amount VARCHAR(12),
    transmission_datetime VARCHAR(14),
    rrn VARCHAR(12),
    auth_code VARCHAR(8),
    terminal_id VARCHAR(16),
    merchant_id VARCHAR(32),
    currency_code VARCHAR(3),
    state VARCHAR(32) NOT NULL DEFAULT 'AUTHORISED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_iso_txn_composite UNIQUE (stan, masked_pan)
);

CREATE INDEX IF NOT EXISTS idx_iso_txn_stan ON iso_transactions (stan);
CREATE INDEX IF NOT EXISTS idx_iso_txn_state ON iso_transactions (state);
CREATE INDEX IF NOT EXISTS idx_iso_txn_created_at ON iso_transactions (created_at);

-- 2. Clearing & Settlement Batches (DMS 1240 / 1644)
CREATE TABLE IF NOT EXISTS iso_clearing_batches (
    id BIGSERIAL PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL UNIQUE,
    settlement_date VARCHAR(8) NOT NULL,
    network_id VARCHAR(32) NOT NULL,
    total_transactions INT NOT NULL DEFAULT 0,
    presentment_count INT NOT NULL DEFAULT 0,
    chargeback_count INT NOT NULL DEFAULT 0,
    fee_collection_count INT NOT NULL DEFAULT 0,
    total_gross_amount_iso VARCHAR(12) NOT NULL,
    total_interchange_fee_iso VARCHAR(12) NOT NULL,
    net_settlement_amount_iso VARCHAR(12) NOT NULL,
    raw_batch_file TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_iso_clearing_batch_date ON iso_clearing_batches (settlement_date);

-- 3. Clearing Records
CREATE TABLE IF NOT EXISTS iso_clearing_records (
    id BIGSERIAL PRIMARY KEY,
    record_id VARCHAR(64) NOT NULL UNIQUE,
    batch_id VARCHAR(64) REFERENCES iso_clearing_batches(batch_id) ON DELETE CASCADE,
    record_type VARCHAR(32) NOT NULL,
    stan VARCHAR(12) NOT NULL,
    masked_pan VARCHAR(32) NOT NULL,
    original_amount_iso VARCHAR(12) NOT NULL,
    settlement_amount_iso VARCHAR(12) NOT NULL,
    interchange_fee_iso VARCHAR(12) NOT NULL,
    currency_code VARCHAR(3) NOT NULL,
    dispute_reason_code VARCHAR(8),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_iso_clearing_rec_batch ON iso_clearing_records (batch_id);
CREATE INDEX IF NOT EXISTS idx_iso_clearing_rec_stan ON iso_clearing_records (stan);

-- 4. Transactional Outbox Events (for Kafka Event Streaming)
CREATE TABLE IF NOT EXISTS iso_outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_iso_outbox_status ON iso_outbox_events (status, created_at);

-- 5. Persistent Cryptographic Key Registry
CREATE TABLE IF NOT EXISTS iso_crypto_keys (
    id BIGSERIAL PRIMARY KEY,
    key_id VARCHAR(64) NOT NULL UNIQUE,
    key_type VARCHAR(32) NOT NULL,
    key_hex_encrypted VARCHAR(256) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    key_length_bytes INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
