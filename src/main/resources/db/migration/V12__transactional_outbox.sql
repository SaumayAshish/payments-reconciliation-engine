CREATE TABLE outbox_event
(
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    event_type text NOT NULL,
    schema_version integer NOT NULL,
    aggregate_id text NOT NULL,
    topic text NOT NULL,
    message_key text NOT NULL,
    payload text NOT NULL,
    occurred_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    published_at timestamptz,
    CONSTRAINT ck_outbox_event_type CHECK (event_type IN ('RUN_COMPLETED', 'CASE_CREATED')),
    CONSTRAINT ck_outbox_schema_version CHECK (schema_version = 1),
    CONSTRAINT ck_outbox_aggregate CHECK (length(btrim(aggregate_id)) > 0),
    CONSTRAINT ck_outbox_topic CHECK (length(btrim(topic)) > 0),
    CONSTRAINT ck_outbox_key CHECK (length(btrim(message_key)) > 0),
    CONSTRAINT ck_outbox_payload CHECK (length(btrim(payload)) > 0),
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    CONSTRAINT ck_outbox_delivery_state CHECK (
        (status = 'PENDING' AND lease_token IS NULL AND lease_until IS NULL AND published_at IS NULL)
        OR (status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_until IS NOT NULL
            AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND lease_token IS NULL AND lease_until IS NULL
            AND published_at IS NOT NULL))
);

CREATE INDEX ix_outbox_pending
    ON outbox_event (next_attempt_at, seq)
    WHERE status = 'PENDING';

CREATE INDEX ix_outbox_lease
    ON outbox_event (lease_until, seq)
    WHERE status = 'IN_FLIGHT';

CREATE FUNCTION guard_outbox_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seq IS DISTINCT FROM OLD.seq
        OR NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.topic IS DISTINCT FROM OLD.topic
        OR NEW.message_key IS DISTINCT FROM OLD.message_key
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at THEN
        RAISE EXCEPTION 'outbox event identity and payload are immutable'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_outbox_identity
    BEFORE UPDATE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION guard_outbox_identity();