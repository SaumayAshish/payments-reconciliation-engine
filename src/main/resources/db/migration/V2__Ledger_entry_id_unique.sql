-- An entry_id identifies one ledger entry in its source system, so it is unique across all ledger files
-- (FRD-03, AC-03.4). The application resolves duplicates and conflicts under an advisory lock and reports them as
-- rejected lines; this index is the backstop that makes a second copy impossible even if that logic were bypassed.
DROP INDEX IF EXISTS ix_ledger_entry_entry_id;
CREATE UNIQUE INDEX uq_ledger_entry_entry_id ON ledger_entry (entry_id) WHERE entry_id IS NOT NULL;