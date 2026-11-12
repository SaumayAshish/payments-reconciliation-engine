-- Durable deduplication for the run-completed case-intake consumer.
-- This marker commits in the same transaction as case intake.
CREATE TABLE processed_run_event (
    event_id UUID PRIMARY KEY,
    run_id BIGINT NOT NULL CHECK (run_id > 0),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);