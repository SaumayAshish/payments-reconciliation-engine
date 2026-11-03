-- V9: maker-checker approvals (ADR-0011, FRD-08).
-- 1. A case can now be resolved by a write-off or an adjustment.
-- 2. Those resolutions claim the case's lines, so the claim sources grow to match.
-- 3. approval_request holds the pending and decided requests.

ALTER TABLE exception_case DROP CONSTRAINT ck_case_resolution;
ALTER TABLE exception_case ADD CONSTRAINT ck_case_resolution CHECK (
    (status = 'RESOLVED' AND resolution_type IN ('MANUAL_MATCH', 'SPLIT', 'WRITE_OFF', 'ADJUSTMENT')
        AND resolution_reason IS NOT NULL AND length(btrim(resolution_reason)) > 0 AND resolved_at IS NOT NULL)
    OR (status <> 'RESOLVED' AND resolution_type IS NULL AND resolution_reason IS NULL AND resolved_at IS NULL));

-- V5 declared the allowed sources twice: inline on the column and again inside ck_claim_source.
ALTER TABLE line_claim DROP CONSTRAINT line_claim_source_check;
ALTER TABLE line_claim DROP CONSTRAINT ck_claim_source;
ALTER TABLE line_claim ADD CONSTRAINT ck_claim_source CHECK (
    (source = 'RUN_MATCH' AND action = 'CLAIM' AND run_id IS NOT NULL AND run_match_ordinal IS NOT NULL
        AND case_id IS NULL)
    OR (source IN ('MANUAL_MATCH', 'SPLIT', 'WRITE_OFF', 'ADJUSTMENT') AND action = 'CLAIM' AND case_id IS NOT NULL
        AND run_id IS NULL AND run_match_ordinal IS NULL AND actor IS NOT NULL)
    OR (source = 'UNMATCH' AND action = 'RELEASE' AND case_id IS NOT NULL
        AND run_id IS NULL AND run_match_ordinal IS NULL AND actor IS NOT NULL));

CREATE TABLE approval_request
(
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    case_id        bigint      NOT NULL REFERENCES exception_case (id),
    action         text        NOT NULL,
    amount_minor   bigint      NOT NULL,
    currency       char(3)     NOT NULL,
    reason         text        NOT NULL,
    maker          text        NOT NULL,
    requested_at   timestamptz NOT NULL DEFAULT now(),
    -- 0 means the built-in defaults; Stage F points this at config_version.
    config_version bigint      NOT NULL DEFAULT 0,
    status         text        NOT NULL DEFAULT 'PENDING',
    checker        text,
    decided_at     timestamptz,
    decision_note  text,
    CONSTRAINT ck_approval_action CHECK (action IN ('WRITE_OFF', 'ADJUSTMENT')),
    CONSTRAINT ck_approval_amount CHECK (amount_minor <> 0),
    CONSTRAINT ck_approval_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_approval_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_approval_maker CHECK (length(btrim(maker)) BETWEEN 1 AND 100),
    CONSTRAINT ck_approval_config CHECK (config_version >= 0),
    CONSTRAINT ck_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_decision CHECK (
        (status = 'PENDING' AND checker IS NULL AND decided_at IS NULL AND decision_note IS NULL)
        OR (status = 'APPROVED' AND checker IS NOT NULL AND decided_at IS NOT NULL)
        OR (status = 'REJECTED' AND checker IS NOT NULL AND decided_at IS NOT NULL
            AND decision_note IS NOT NULL AND length(btrim(decision_note)) > 0)),
    CONSTRAINT ck_approval_checker CHECK (checker IS NULL OR length(btrim(checker)) BETWEEN 1 AND 100),
    -- Separation of duties enforced by the database as the last line of defence.
    CONSTRAINT ck_approval_separation CHECK (checker IS NULL OR lower(checker) <> lower(maker))
);

-- One open request per case.
CREATE UNIQUE INDEX uq_approval_pending_case ON approval_request (case_id) WHERE status = 'PENDING';
-- The approval queue, oldest first.
CREATE INDEX ix_approval_queue ON approval_request (requested_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_approval_case ON approval_request (case_id, id);

-- A request is decided once, and nothing about what was asked can change.
CREATE FUNCTION guard_approval_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'approval_request rows are never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status <> 'PENDING' THEN
        RAISE EXCEPTION 'a decided approval request cannot change' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.case_id <> OLD.case_id OR NEW.action <> OLD.action
        OR NEW.amount_minor <> OLD.amount_minor OR NEW.currency <> OLD.currency OR NEW.reason <> OLD.reason
        OR NEW.maker <> OLD.maker OR NEW.requested_at <> OLD.requested_at
        OR NEW.config_version <> OLD.config_version THEN
        RAISE EXCEPTION 'the request of an approval cannot change' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_request_guard
    BEFORE UPDATE OR DELETE ON approval_request FOR EACH ROW EXECUTE FUNCTION guard_approval_request();