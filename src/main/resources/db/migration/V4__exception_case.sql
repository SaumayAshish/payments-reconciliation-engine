-- V4: exception cases (ADR-0007, ADR-0008).
-- Cases are mutable workflow beside the append-only run results of V3.

CREATE TABLE exception_case (
    id                    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    origin                text        NOT NULL,
    origin_run_id         bigint,
    origin_ordinal        integer,
    linked_case_id        bigint REFERENCES exception_case (id),
    natural_key           text        NOT NULL,
    kind                  text        NOT NULL,
    case_class            text        NOT NULL,
    amount_minor          bigint      NOT NULL,
    currency              char(3)     NOT NULL,
    status                text        NOT NULL DEFAULT 'OPEN',
    priority              text        NOT NULL,
    team                  text        NOT NULL,
    routing_rule          text        NOT NULL,
    owner                 text,
    created_business_date date        NOT NULL,
    sla_business_days     integer     NOT NULL,
    due_business_date     date        NOT NULL,
    resolution_type       text,
    resolution_reason     text,
    resolved_at           timestamptz,
    version               bigint      NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_case_origin FOREIGN KEY (origin_run_id, origin_ordinal)
        REFERENCES run_exception (run_id, ordinal),
    CONSTRAINT ck_case_origin CHECK (
        (origin = 'RUN' AND origin_run_id IS NOT NULL AND origin_ordinal IS NOT NULL AND linked_case_id IS NULL)
        OR (origin = 'UNMATCH' AND origin_run_id IS NULL AND origin_ordinal IS NULL AND linked_case_id IS NOT NULL)),
    CONSTRAINT ck_case_key CHECK (length(btrim(natural_key)) > 0),
    CONSTRAINT ck_case_kind CHECK (kind IN ('MISSING_LEDGER', 'MISSING_STATEMENT', 'AMOUNT_MISMATCH',
        'CURRENCY_MISMATCH', 'DUPLICATE_SUSPECT', 'LOW_CONFIDENCE', 'AMBIGUOUS_TIE')),
    CONSTRAINT ck_case_class CHECK (
        (kind IN ('LOW_CONFIDENCE', 'AMBIGUOUS_TIE') AND case_class = 'REVIEW')
        OR (kind NOT IN ('LOW_CONFIDENCE', 'AMBIGUOUS_TIE') AND case_class = 'BREAK')),
    CONSTRAINT ck_case_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_case_status CHECK (status IN ('OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'ESCALATED')),
    CONSTRAINT ck_case_priority CHECK (priority IN ('P1', 'P2', 'P3', 'P4')),
    CONSTRAINT ck_case_team CHECK (team IN ('FX_TEAM', 'SENIOR_ANALYST', 'LEDGER_OPERATIONS',
        'BANK_OPERATIONS', 'RECON_ANALYSTS', 'TEAM_LEAD')),
    CONSTRAINT ck_case_owner CHECK (
        (status = 'OPEN' AND owner IS NULL)
        OR (status IN ('ASSIGNED', 'INVESTIGATING') AND owner IS NOT NULL)
        OR status IN ('RESOLVED', 'ESCALATED')),
    CONSTRAINT ck_case_sla CHECK (sla_business_days > 0 AND due_business_date >= created_business_date),
    CONSTRAINT ck_case_resolution CHECK (
        (status = 'RESOLVED' AND resolution_type IN ('MANUAL_MATCH', 'SPLIT')
            AND resolution_reason IS NOT NULL AND length(btrim(resolution_reason)) > 0 AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolution_type IS NULL AND resolution_reason IS NULL AND resolved_at IS NULL)),
    CONSTRAINT ck_case_version CHECK (version >= 0)
);

-- AC-06.3: a rerun never opens a second case for the same exception. Unmatch cases are exempt by design.
CREATE UNIQUE INDEX uq_case_run_natural_key ON exception_case (natural_key) WHERE origin = 'RUN';
-- The work queue: unresolved cases by team, priority and due date.
CREATE INDEX ix_case_open_queue ON exception_case (team, priority, due_business_date, id) WHERE status <> 'RESOLVED';
CREATE INDEX ix_case_owner ON exception_case (owner) WHERE owner IS NOT NULL;
CREATE INDEX ix_case_origin ON exception_case (origin_run_id, origin_ordinal);

CREATE FUNCTION guard_exception_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'exception_case rows are never deleted' USING ERRCODE = 'restrict_violation';
    END IF;
    IF OLD.status = 'RESOLVED' THEN
        RAISE EXCEPTION 'a resolved case is final' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.origin IS DISTINCT FROM OLD.origin
        OR NEW.origin_run_id IS DISTINCT FROM OLD.origin_run_id
        OR NEW.origin_ordinal IS DISTINCT FROM OLD.origin_ordinal
        OR NEW.linked_case_id IS DISTINCT FROM OLD.linked_case_id
        OR NEW.natural_key IS DISTINCT FROM OLD.natural_key
        OR NEW.kind IS DISTINCT FROM OLD.kind
        OR NEW.case_class IS DISTINCT FROM OLD.case_class
        OR NEW.amount_minor IS DISTINCT FROM OLD.amount_minor
        OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.created_business_date IS DISTINCT FROM OLD.created_business_date
        OR NEW.sla_business_days IS DISTINCT FROM OLD.sla_business_days
        OR NEW.due_business_date IS DISTINCT FROM OLD.due_business_date
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'exception_case identity, amount and SLA columns are immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'exception_case version must rise by exactly one' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
        (OLD.status = 'OPEN' AND NEW.status IN ('ASSIGNED', 'ESCALATED'))
        OR (OLD.status = 'ASSIGNED' AND NEW.status IN ('INVESTIGATING', 'ESCALATED'))
        OR (OLD.status = 'INVESTIGATING' AND NEW.status IN ('RESOLVED', 'ESCALATED'))
        OR (OLD.status = 'ESCALATED' AND NEW.status IN ('INVESTIGATING', 'ASSIGNED'))) THEN
        RAISE EXCEPTION 'illegal case transition % to %', OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exception_case_guard_update BEFORE UPDATE ON exception_case
    FOR EACH ROW EXECUTE FUNCTION guard_exception_case();
CREATE TRIGGER trg_exception_case_guard_delete BEFORE DELETE ON exception_case
    FOR EACH ROW EXECUTE FUNCTION guard_exception_case();

-- Timeline of everything that happened to a case.
CREATE TABLE case_activity (
    case_id       bigint      NOT NULL REFERENCES exception_case (id),
    ordinal       integer     NOT NULL,
    activity_type text        NOT NULL,
    actor         text        NOT NULL,
    from_status   text,
    to_status     text,
    detail        text,
    occurred_at   timestamptz NOT NULL,
    PRIMARY KEY (case_id, ordinal),
    CONSTRAINT ck_activity_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_activity_type CHECK (activity_type IN ('CREATED', 'ASSIGNED', 'COMMENT_ADDED', 'STATUS_CHANGED',
        'MATCH_ATTEMPTED', 'MATCH_REJECTED', 'ESCALATED', 'APPROVAL_REQUESTED', 'APPROVAL_APPROVED',
        'APPROVAL_REJECTED', 'RESOLVED')),
    CONSTRAINT ck_activity_actor CHECK (length(btrim(actor)) BETWEEN 1 AND 100),
    CONSTRAINT ck_activity_status_pair CHECK ((from_status IS NULL) = (to_status IS NULL)),
    CONSTRAINT ck_activity_from CHECK (from_status IS NULL OR from_status IN
        ('OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'ESCALATED')),
    CONSTRAINT ck_activity_to CHECK (to_status IS NULL OR to_status IN
        ('OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'ESCALATED'))
);

-- Owner history: the previous owner is always recoverable (AC-07.3).
CREATE TABLE case_assignment (
    case_id     bigint      NOT NULL REFERENCES exception_case (id),
    ordinal     integer     NOT NULL,
    owner       text        NOT NULL,
    team        text        NOT NULL,
    assigned_by text        NOT NULL,
    reason      text,
    assigned_at timestamptz NOT NULL,
    PRIMARY KEY (case_id, ordinal),
    CONSTRAINT ck_assignment_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_assignment_owner CHECK (length(btrim(owner)) BETWEEN 1 AND 100),
    CONSTRAINT ck_assignment_by CHECK (length(btrim(assigned_by)) BETWEEN 1 AND 100),
    CONSTRAINT ck_assignment_team CHECK (team IN ('FX_TEAM', 'SENIOR_ANALYST', 'LEDGER_OPERATIONS',
        'BANK_OPERATIONS', 'RECON_ANALYSTS', 'TEAM_LEAD'))
);

CREATE TABLE case_comment (
    case_id    bigint      NOT NULL REFERENCES exception_case (id),
    ordinal    integer     NOT NULL,
    author     text        NOT NULL,
    body       text        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (case_id, ordinal),
    CONSTRAINT ck_comment_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_comment_author CHECK (length(btrim(author)) BETWEEN 1 AND 100),
    CONSTRAINT ck_comment_body CHECK (length(btrim(body)) BETWEEN 1 AND 4000)
);

-- Metadata only: the file itself is never stored.
CREATE TABLE case_attachment (
    case_id      bigint      NOT NULL REFERENCES exception_case (id),
    ordinal      integer     NOT NULL,
    file_name    text        NOT NULL,
    content_type text        NOT NULL,
    size_bytes   bigint      NOT NULL,
    sha256       char(64)    NOT NULL,
    added_by     text        NOT NULL,
    added_at     timestamptz NOT NULL,
    PRIMARY KEY (case_id, ordinal),
    CONSTRAINT ck_attachment_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_attachment_name CHECK (length(btrim(file_name)) BETWEEN 1 AND 255),
    CONSTRAINT ck_attachment_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_attachment_hash CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_attachment_by CHECK (length(btrim(added_by)) BETWEEN 1 AND 100)
);

CREATE TRIGGER trg_case_activity_immutable BEFORE UPDATE OR DELETE ON case_activity
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_case_assignment_immutable BEFORE UPDATE OR DELETE ON case_assignment
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_case_comment_immutable BEFORE UPDATE OR DELETE ON case_comment
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_case_attachment_immutable BEFORE UPDATE OR DELETE ON case_attachment
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();