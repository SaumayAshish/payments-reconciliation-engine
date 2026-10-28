-- V7: unmatching an auto-confirmed run match opens a case that has no earlier case to link to (FRD-12, ADR-0009).
-- An unmatch case may now be unlinked, but it still never points at a run exception.
ALTER TABLE exception_case DROP CONSTRAINT ck_case_origin;
ALTER TABLE exception_case ADD CONSTRAINT ck_case_origin CHECK (
    (origin = 'RUN' AND origin_run_id IS NOT NULL AND origin_ordinal IS NOT NULL AND linked_case_id IS NULL)
    OR (origin = 'UNMATCH' AND origin_run_id IS NULL AND origin_ordinal IS NULL));