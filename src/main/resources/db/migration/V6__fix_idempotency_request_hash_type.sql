ALTER TABLE idempotency_records
ALTER COLUMN request_hash TYPE VARCHAR(64)
    USING request_hash::VARCHAR(64);