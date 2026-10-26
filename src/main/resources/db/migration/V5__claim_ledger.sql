-- V5: claim ledger (ADR-0009).
-- A matched line is claimed once. Claims and releases are append-only rows; a release names the claim it ends.

ALTER TABLE reconciliation_run
    ADD COLUMN claim_cutoff_seq bigint NOT NULL DEFAULT 0 CHECK (claim_cutoff_seq >= 0);

-- The definition of a run now includes its claim cut-off. Same function as V3 with one more immutable column.
CREATE OR REPLACE FUNCTION guard_reconciliation_run() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'table % is append-only: DELETE is not permitted', TG_TABLE_NAME
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.id <> OLD.id OR NEW.business_date <> OLD.business_date
        OR NEW.ledger_cutoff_seq <> OLD.ledger_cutoff_seq OR NEW.claim_cutoff_seq <> OLD.claim_cutoff_seq
        OR NEW.created_at <> OLD.created_at
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

CREATE TABLE line_claim
(
    seq               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action            text        NOT NULL CHECK (action IN ('CLAIM', 'RELEASE')),
    statement_seq     bigint REFERENCES statement_line (seq),
    ledger_seq        bigint REFERENCES ledger_entry (seq),
    source            text        NOT NULL CHECK (source IN ('RUN_MATCH', 'MANUAL_MATCH', 'SPLIT', 'UNMATCH')),
    run_id            bigint,
    run_match_ordinal integer,
    case_id           bigint REFERENCES exception_case (id),
    releases_seq      bigint REFERENCES line_claim (seq),
    actor             text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (run_id, run_match_ordinal) REFERENCES run_match (run_id, ordinal),
    CONSTRAINT ck_claim_one_side CHECK ((statement_seq IS NULL) <> (ledger_seq IS NULL)),
    CONSTRAINT ck_claim_release CHECK ((action = 'RELEASE') = (releases_seq IS NOT NULL)),
    CONSTRAINT ck_claim_source CHECK (
        (source = 'RUN_MATCH' AND action = 'CLAIM' AND run_id IS NOT NULL AND run_match_ordinal IS NOT NULL
            AND case_id IS NULL)
        OR (source IN ('MANUAL_MATCH', 'SPLIT') AND action = 'CLAIM' AND case_id IS NOT NULL
            AND run_id IS NULL AND run_match_ordinal IS NULL AND actor IS NOT NULL)
        OR (source = 'UNMATCH' AND action = 'RELEASE' AND case_id IS NOT NULL
            AND run_id IS NULL AND run_match_ordinal IS NULL AND actor IS NOT NULL)),
    CONSTRAINT ck_claim_actor CHECK (actor IS NULL OR length(btrim(actor)) BETWEEN 1 AND 100)
);

-- A claim is released at most once.
CREATE UNIQUE INDEX uq_claim_release ON line_claim (releases_seq) WHERE releases_seq IS NOT NULL;
-- Lookups by line (the active-claim check and loadInputs) and by what made the claim (unmatch).
CREATE INDEX ix_claim_statement ON line_claim (statement_seq) WHERE statement_seq IS NOT NULL;
CREATE INDEX ix_claim_ledger ON line_claim (ledger_seq) WHERE ledger_seq IS NOT NULL;
CREATE INDEX ix_claim_case ON line_claim (case_id) WHERE case_id IS NOT NULL;
CREATE INDEX ix_claim_run_match ON line_claim (run_id, run_match_ordinal) WHERE run_id IS NOT NULL;

-- One active claim per line, whoever writes it. The per-line advisory lock makes the check-then-insert safe
-- when two transactions claim the same line at once.
CREATE FUNCTION guard_line_claim() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('line_claim:' ||
        CASE WHEN NEW.statement_seq IS NOT NULL THEN 'S' || NEW.statement_seq ELSE 'L' || NEW.ledger_seq END, 0));
    IF NEW.action = 'CLAIM' THEN
        IF EXISTS (SELECT 1
                   FROM line_claim c
                   WHERE c.action = 'CLAIM'
                     AND ((NEW.statement_seq IS NOT NULL AND c.statement_seq = NEW.statement_seq)
                       OR (NEW.ledger_seq IS NOT NULL AND c.ledger_seq = NEW.ledger_seq))
                     AND NOT EXISTS (SELECT 1 FROM line_claim r WHERE r.releases_seq = c.seq)) THEN
            RAISE EXCEPTION 'the line is already claimed by an active match'
                USING ERRCODE = 'unique_violation';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1
                       FROM line_claim c
                       WHERE c.seq = NEW.releases_seq
                         AND c.action = 'CLAIM'
                         AND ((NEW.statement_seq IS NOT NULL AND c.statement_seq = NEW.statement_seq)
                           OR (NEW.ledger_seq IS NOT NULL AND c.ledger_seq = NEW.ledger_seq))) THEN
            RAISE EXCEPTION 'a release must name a claim on the same line'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_line_claim_guard
    BEFORE INSERT ON line_claim FOR EACH ROW EXECUTE FUNCTION guard_line_claim();
CREATE TRIGGER trg_line_claim_immutable
    BEFORE UPDATE OR DELETE ON line_claim FOR EACH ROW EXECUTE FUNCTION reject_mutation();