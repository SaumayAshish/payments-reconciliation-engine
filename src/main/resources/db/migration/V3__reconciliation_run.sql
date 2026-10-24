-- V3: reconciliation runs and their stored output (FRD-10, FRD-05, FRD-06; ADR-0003).
--
-- A run is defined by (business date, matching configuration, statement ingestions, ledger cut-off seq). Those
-- four inputs are stored on the run, so a replay needs nothing else. The matching configuration is stored as a
-- snapshot on the run row: a configuration change later can never alter what an old run used. A versioned,
-- audited configuration table arrives with Phase 5.
--
-- The result tables are append-only evidence, like the input tables in V1.

CREATE TABLE reconciliation_run
(
    id                              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_date                   date        NOT NULL,
    status                          text        NOT NULL CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED')),
    -- Highest ledger_entry.seq this run may read. Taken under an exclusive advisory lock, so every seq at or
    -- below it is committed (ADR-0003 amendment).
    ledger_cutoff_seq               bigint      NOT NULL CHECK (ledger_cutoff_seq >= 0),
    auto_threshold_milli            integer     NOT NULL CHECK (auto_threshold_milli BETWEEN 1 AND 1000),
    review_threshold_milli          integer     NOT NULL CHECK (review_threshold_milli BETWEEN 1 AND 999),
    date_window_business_days       integer     NOT NULL CHECK (date_window_business_days BETWEEN 1 AND 30),
    lookback_business_days          integer     NOT NULL CHECK (lookback_business_days BETWEEN 1 AND 60),
    max_one_to_many_candidates      integer     NOT NULL CHECK (max_one_to_many_candidates BETWEEN 2 AND 12),
    fuzzy_min_similarity_milli      integer     NOT NULL CHECK (fuzzy_min_similarity_milli BETWEEN 1 AND 999),
    fuzzy_counterparty_weight_milli integer     NOT NULL CHECK (fuzzy_counterparty_weight_milli BETWEEN 0 AND 1000),
    created_at                      timestamptz NOT NULL DEFAULT now(),
    started_at                      timestamptz,
    completed_at                    timestamptz,
    duration_millis                 bigint CHECK (duration_millis >= 0),
    output_hash                     char(64) CHECK (output_hash ~ '^[0-9a-f]{64}$'),
    statement_line_count            integer CHECK (statement_line_count >= 0),
    ledger_entry_count              integer CHECK (ledger_entry_count >= 0),
    match_count                     integer CHECK (match_count >= 0),
    auto_match_count                integer CHECK (auto_match_count >= 0),
    review_match_count              integer CHECK (review_match_count >= 0),
    ambiguous_group_count           integer CHECK (ambiguous_group_count >= 0),
    exception_count                 integer CHECK (exception_count >= 0),
    carried_forward_count           integer CHECK (carried_forward_count >= 0),
    truncation_note_count           integer CHECK (truncation_note_count >= 0),
    failure_reason                  text,
    CONSTRAINT ck_run_thresholds CHECK (review_threshold_milli < auto_threshold_milli),
    CONSTRAINT ck_run_lookback CHECK (lookback_business_days >= date_window_business_days),
    -- A completed run always carries its hash and counts; a failed run always says why; nothing else does.
    CONSTRAINT ck_run_state CHECK (
        (status = 'CREATED' AND started_at IS NULL AND completed_at IS NULL AND output_hash IS NULL
            AND failure_reason IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL AND output_hash IS NULL
            AND failure_reason IS NULL)
        OR (status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL
            AND output_hash IS NOT NULL AND duration_millis IS NOT NULL AND failure_reason IS NULL
            AND statement_line_count IS NOT NULL AND ledger_entry_count IS NOT NULL AND match_count IS NOT NULL
            AND auto_match_count IS NOT NULL AND review_match_count IS NOT NULL
            AND ambiguous_group_count IS NOT NULL AND exception_count IS NOT NULL
            AND carried_forward_count IS NOT NULL AND truncation_note_count IS NOT NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND output_hash IS NULL AND failure_reason IS NOT NULL)
    )
);

-- The statement ingestions a run reconciles. Ingestions are immutable and committed atomically with their
-- lines, so naming them is a complete, race-free definition of the statement side.
CREATE TABLE reconciliation_run_statement_batch
(
    run_id       bigint NOT NULL REFERENCES reconciliation_run (id),
    ingestion_id bigint NOT NULL REFERENCES ingestion_file (id),
    PRIMARY KEY (run_id, ingestion_id)
);

-- A run may only move forward, and its definition never changes (FRD-10 AC-10.1).
CREATE FUNCTION guard_reconciliation_run() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'table % is append-only: DELETE is not permitted', TG_TABLE_NAME
            USING ERRCODE = 'restrict_violation';
END IF;
    IF NEW.id <> OLD.id OR NEW.business_date <> OLD.business_date
        OR NEW.ledger_cutoff_seq <> OLD.ledger_cutoff_seq OR NEW.created_at <> OLD.created_at
        OR NEW.auto_threshold_milli <> OLD.auto_threshold_milli
        OR NEW.review_threshold_milli <> OLD.review_threshold_milli
        OR NEW.date_window_business_days <> OLD.date_window_business_days
        OR NEW.lookback_business_days <> OLD.lookback_business_days
        OR NEW.max_one_to_many_candidates <> OLD.max_one_to_many_candidates
        OR NEW.fuzzy_min_similarity_milli <> OLD.fuzzy_min_similarity_milli
        OR NEW.fuzzy_counterparty_weight_milli <> OLD.fuzzy_counterparty_weight_milli THEN
        RAISE EXCEPTION 'the definition of a reconciliation run cannot change'
            USING ERRCODE = 'restrict_violation';
END IF;
    IF NOT ((OLD.status = 'CREATED' AND NEW.status IN ('RUNNING', 'FAILED'))
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'illegal run status change % -> %', OLD.status, NEW.status
            USING ERRCODE = 'restrict_violation';
END IF;
RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reconciliation_run_guard
    BEFORE UPDATE OR DELETE ON reconciliation_run FOR EACH ROW EXECUTE FUNCTION guard_reconciliation_run();
CREATE TRIGGER trg_run_statement_batch_immutable
    BEFORE UPDATE OR DELETE ON reconciliation_run_statement_batch FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- One row per match. (run_id, ordinal) is assigned by the application so a whole run is written with plain
-- batch inserts, with no round trip per row.
CREATE TABLE run_match
(
    run_id      bigint  NOT NULL REFERENCES reconciliation_run (id),
    ordinal     integer NOT NULL CHECK (ordinal >= 1),
    tier        text    NOT NULL CHECK (tier IN ('EXACT', 'TOLERANCE', 'FUZZY', 'ONE_TO_MANY')),
    score_milli integer NOT NULL CHECK (score_milli BETWEEN 0 AND 1000),
    band        text    NOT NULL CHECK (band IN ('AUTO_CONFIRM', 'REVIEW', 'EXCEPTION')),
    reason      text    NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

-- The lines of a match or exception. A line is a statement line or a ledger entry, never both and never
-- neither; each side has its own foreign key, so a dangling reference is impossible.
CREATE TABLE run_match_line
(
    run_id        bigint  NOT NULL,
    match_ordinal integer NOT NULL,
    position      integer NOT NULL CHECK (position >= 1),
    statement_seq bigint REFERENCES statement_line (seq),
    ledger_seq    bigint REFERENCES ledger_entry (seq),
    PRIMARY KEY (run_id, match_ordinal, position),
    FOREIGN KEY (run_id, match_ordinal) REFERENCES run_match (run_id, ordinal),
    CONSTRAINT ck_run_match_line_one_side CHECK ((statement_seq IS NULL) <> (ledger_seq IS NULL))
);

CREATE TABLE run_exception
(
    run_id      bigint  NOT NULL REFERENCES reconciliation_run (id),
    ordinal     integer NOT NULL CHECK (ordinal >= 1),
    kind        text    NOT NULL CHECK (kind IN ('MISSING_LEDGER', 'MISSING_STATEMENT', 'AMOUNT_MISMATCH',
                                                 'CURRENCY_MISMATCH', 'LOW_CONFIDENCE', 'AMBIGUOUS_TIE')),
    natural_key text    NOT NULL,
    reason      text    NOT NULL,
    detail      text    NOT NULL,
    PRIMARY KEY (run_id, ordinal),
    -- FRD-06 AC-06.3: one exception per kind and first subject line in a run.
    CONSTRAINT uq_run_exception_natural_key UNIQUE (run_id, natural_key)
);

CREATE TABLE run_exception_line
(
    run_id            bigint  NOT NULL,
    exception_ordinal integer NOT NULL,
    role              text    NOT NULL CHECK (role IN ('SUBJECT', 'CANDIDATE')),
    position          integer NOT NULL CHECK (position >= 1),
    statement_seq     bigint REFERENCES statement_line (seq),
    ledger_seq        bigint REFERENCES ledger_entry (seq),
    PRIMARY KEY (run_id, exception_ordinal, role, position),
    FOREIGN KEY (run_id, exception_ordinal) REFERENCES run_exception (run_id, ordinal),
    CONSTRAINT ck_run_exception_line_one_side CHECK ((statement_seq IS NULL) <> (ledger_seq IS NULL))
);

-- Ledger entries a run saw and left alone because they are still inside the lookback window (Q-05).
CREATE TABLE run_carried_forward
(
    run_id     bigint NOT NULL REFERENCES reconciliation_run (id),
    ledger_seq bigint NOT NULL REFERENCES ledger_entry (seq),
    PRIMARY KEY (run_id, ledger_seq)
);

-- Engine notes such as a truncated one-to-many search (FRD-04 AC-04.5).
CREATE TABLE run_note
(
    run_id  bigint  NOT NULL REFERENCES reconciliation_run (id),
    ordinal integer NOT NULL CHECK (ordinal >= 1),
    note    text    NOT NULL,
    PRIMARY KEY (run_id, ordinal)
);

-- Reverse lookups: which run decided about this line. The primary keys already serve "everything in run X".
CREATE INDEX ix_run_match_line_statement ON run_match_line (statement_seq) WHERE statement_seq IS NOT NULL;
CREATE INDEX ix_run_match_line_ledger ON run_match_line (ledger_seq) WHERE ledger_seq IS NOT NULL;
CREATE INDEX ix_run_exception_line_statement ON run_exception_line (statement_seq) WHERE statement_seq IS NOT NULL;
CREATE INDEX ix_run_exception_line_ledger ON run_exception_line (ledger_seq) WHERE ledger_seq IS NOT NULL;
CREATE INDEX ix_reconciliation_run_date ON reconciliation_run (business_date, id);

CREATE TRIGGER trg_run_match_immutable
    BEFORE UPDATE OR DELETE ON run_match FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_run_match_line_immutable
    BEFORE UPDATE OR DELETE ON run_match_line FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_run_exception_immutable
    BEFORE UPDATE OR DELETE ON run_exception FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_run_exception_line_immutable
    BEFORE UPDATE OR DELETE ON run_exception_line FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_run_carried_forward_immutable
    BEFORE UPDATE OR DELETE ON run_carried_forward FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_run_note_immutable
    BEFORE UPDATE OR DELETE ON run_note FOR EACH ROW EXECUTE FUNCTION reject_mutation();