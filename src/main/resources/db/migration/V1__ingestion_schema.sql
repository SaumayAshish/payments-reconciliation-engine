-- V1: ingestion schema (FRD-01, FRD-02, FRD-03; ADR-0001, ADR-0002, ADR-0005).
--
-- Money is bigint minor units plus a char(3) currency. No numeric or floating point money columns.
-- Inputs are immutable: triggers reject UPDATE and DELETE (TRUNCATE is still allowed so tests can reset state).
-- Targets PostgreSQL 17. Applied by hand on PostgreSQL 16 with 11 constraint and trigger checks passing.
-- The automated proof (Testcontainers on 17) is stage B of Phase 1.

CREATE FUNCTION reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'table % is append-only: % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- One row per ingested file. Identity is (kind, SHA-256 of the raw bytes): the idempotency anchor (ADR-0002).
CREATE TABLE ingestion_file
(
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind           text        NOT NULL CHECK (kind IN ('STATEMENT', 'LEDGER')),
    file_hash      char(64)    NOT NULL CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    format         text        NOT NULL CHECK (format IN ('CSV', 'CAMT053', 'JSON')),
    byte_size      bigint      NOT NULL CHECK (byte_size >= 0),
    accepted_count integer     NOT NULL CHECK (accepted_count >= 0),
    rejected_count integer     NOT NULL CHECK (rejected_count >= 0),
    ingested_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_ingestion_kind_hash UNIQUE (kind, file_hash)
);

-- Statement lines and ledger entries share one canonical shape (FRD section 4.1).
-- seq is assigned at insert and used for replay cut-offs and tie-breaks (ADR-0003). See the ADR-0003
-- amendment: seq order is NOT commit order, so the run service must take its cut-off under a lock.
CREATE TABLE statement_line
(
    seq               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingestion_id      bigint   NOT NULL REFERENCES ingestion_file (id),
    line_number       integer  NOT NULL CHECK (line_number >= 1),
    line_key          char(64) NOT NULL CHECK (line_key ~ '^[0-9a-f]{64}$'),
    account           text     NOT NULL CHECK (account ~ '^[A-Z0-9-]{1,34}$'),
    amount_minor      bigint   NOT NULL CHECK (amount_minor <> 0),
    currency          char(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    value_date        date     NOT NULL,
    booking_date      date,
    reference         text     NOT NULL CHECK (length(reference) > 0),
    reference_norm    text     NOT NULL CHECK (length(reference_norm) > 0),
    counterparty      text     NOT NULL DEFAULT '',
    counterparty_norm text     NOT NULL DEFAULT '',
    narrative         text     NOT NULL DEFAULT '' CHECK (length(narrative) <= 280),
    CONSTRAINT uq_statement_line_key UNIQUE (line_key),
    CONSTRAINT uq_statement_line_position UNIQUE (ingestion_id, line_number)
);

CREATE TABLE ledger_entry
(
    seq               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingestion_id      bigint   NOT NULL REFERENCES ingestion_file (id),
    line_number       integer  NOT NULL CHECK (line_number >= 1),
    line_key          char(64) NOT NULL CHECK (line_key ~ '^[0-9a-f]{64}$'),
    entry_id          text,
    account           text     NOT NULL CHECK (account ~ '^[A-Z0-9-]{1,34}$'),
    amount_minor      bigint   NOT NULL CHECK (amount_minor <> 0),
    currency          char(3)  NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    value_date        date     NOT NULL,
    booking_date      date,
    reference         text     NOT NULL CHECK (length(reference) > 0),
    reference_norm    text     NOT NULL CHECK (length(reference_norm) > 0),
    counterparty      text     NOT NULL DEFAULT '',
    counterparty_norm text     NOT NULL DEFAULT '',
    narrative         text     NOT NULL DEFAULT '' CHECK (length(narrative) <= 280),
    CONSTRAINT uq_ledger_entry_key UNIQUE (line_key),
    CONSTRAINT uq_ledger_entry_position UNIQUE (ingestion_id, line_number)
);

-- Rejected lines are kept and queryable, never silently dropped (FRD-02, master prompt section 28).
CREATE TABLE rejected_line
(
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingestion_id  bigint  NOT NULL REFERENCES ingestion_file (id),
    line_number   integer NOT NULL CHECK (line_number >= 1),
    error_code    text    NOT NULL,
    error_message text    NOT NULL,
    raw_excerpt   text    NOT NULL DEFAULT '' CHECK (length(raw_excerpt) <= 200),
    CONSTRAINT uq_rejected_line_position UNIQUE (ingestion_id, line_number)
);

-- Candidate lookup for tiers 1 and 2. The leftmost three columns also serve tier 3 and 4 candidate pools, so a
-- separate (account, currency, amount_minor) index would be redundant.
CREATE INDEX ix_statement_line_match ON statement_line (account, currency, amount_minor, value_date);
CREATE INDEX ix_ledger_entry_match ON ledger_entry (account, currency, amount_minor, value_date);

-- Source-system entry id lookup for conflict detection (FRD AC-03.4). Not unique: the same entry may legitimately
-- reappear in an overlapping file; the ingestion service decides whether content conflicts.
CREATE INDEX ix_ledger_entry_entry_id ON ledger_entry (entry_id) WHERE entry_id IS NOT NULL;

-- Inputs are immutable evidence (ADR-0003). TRUNCATE is deliberately not covered.
CREATE TRIGGER trg_ingestion_file_immutable
    BEFORE UPDATE OR DELETE ON ingestion_file FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_statement_line_immutable
    BEFORE UPDATE OR DELETE ON statement_line FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER trg_rejected_line_immutable
    BEFORE UPDATE OR DELETE ON rejected_line FOR EACH ROW EXECUTE FUNCTION reject_mutation();
