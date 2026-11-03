-- V8: the append-only audit chain (ADR-0004, ADR-0010).
-- One global chain. Each event carries the hash of the one before it, so editing history breaks every later link.
-- seq is assigned by the writer under the chain lock, never by an identity column: an identity sequence skips values
-- when a transaction rolls back, and the verifier would report such a gap as tampering.
-- before_state and after_state are canonical JSON stored as text, not jsonb: jsonb reorders keys and would change
-- the bytes that were hashed.

CREATE TABLE audit_event (
    seq                bigint  NOT NULL,
    scheme             integer NOT NULL,
    entity_type        text    NOT NULL,
    entity_id          text    NOT NULL,
    actor              text    NOT NULL,
    action             text    NOT NULL,
    before_state       text    NOT NULL,
    after_state        text    NOT NULL,
    occurred_at_micros bigint  NOT NULL,
    previous_hash      text    NOT NULL,
    current_hash       text    NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (seq),
    -- One successor per event: a second event claiming the same predecessor would fork the chain.
    CONSTRAINT uq_audit_previous_hash UNIQUE (previous_hash),
    CONSTRAINT uq_audit_current_hash UNIQUE (current_hash),
    CONSTRAINT ck_audit_seq CHECK (seq >= 1),
    CONSTRAINT ck_audit_scheme CHECK (scheme >= 1),
    CONSTRAINT ck_audit_text CHECK (btrim(entity_type) <> '' AND btrim(entity_id) <> ''
        AND btrim(actor) <> '' AND btrim(action) <> ''),
    CONSTRAINT ck_audit_time CHECK (occurred_at_micros >= 0),
    CONSTRAINT ck_audit_hashes CHECK (previous_hash ~ '^[0-9a-f]{64}$' AND current_hash ~ '^[0-9a-f]{64}$')
);

-- The history of one entity, in chain order (GET /api/v1/audit/{entityType}/{entityId}).
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id, seq);

-- History is never edited, removed or emptied. Tests that must reset the chain disable user triggers explicitly.
CREATE FUNCTION reject_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_audit_change();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_change();

-- An insert must continue the chain: next sequence number, linked to the current head. The trigger cannot recompute
-- SHA-256 and does not try; hash correctness is the verifier's job. It takes the chain lock itself (re-entrant for
-- a writer that already holds it), so even a writer that skipped the lock cannot land two events on one head.
-- Lock key 7002 is the chain; 7001 is the claim buckets (V6).
CREATE FUNCTION guard_audit_append() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    head_seq  bigint;
    head_hash text;
BEGIN
    PERFORM pg_advisory_xact_lock(7002, 1);
    SELECT seq, current_hash INTO head_seq, head_hash FROM audit_event ORDER BY seq DESC LIMIT 1;
    IF NOT FOUND THEN
        head_seq := 0;
        head_hash := repeat('0', 64);
    END IF;
    IF NEW.seq <> head_seq + 1 THEN
        RAISE EXCEPTION 'audit sequence must continue from %, not %', head_seq, NEW.seq
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.previous_hash <> head_hash THEN
        RAISE EXCEPTION 'audit event does not link to the head of the chain'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER audit_event_append_guard
    BEFORE INSERT ON audit_event
    FOR EACH ROW EXECUTE FUNCTION guard_audit_append();