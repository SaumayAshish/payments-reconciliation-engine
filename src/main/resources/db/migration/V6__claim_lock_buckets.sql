-- V6: bucket the per-line claim locks (ADR-0009).
-- V5 took one advisory lock per claimed line. Advisory locks live in PostgreSQL's shared lock table until commit, so
-- claiming tens of thousands of lines in one transaction (a large run completing) would exhaust it. Hashing the
-- line into 1,024 buckets bounds the locks per transaction. Two writers on the same line still land on the same
-- bucket and so still serialise; unrelated lines at worst share one.

CREATE OR REPLACE FUNCTION guard_line_claim() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM pg_advisory_xact_lock(7001, (hashtextextended('line_claim:' ||
        CASE WHEN NEW.statement_seq IS NOT NULL THEN 'S' || NEW.statement_seq ELSE 'L' || NEW.ledger_seq END,
        0) & 1023)::int);
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