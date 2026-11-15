-- Existing approvals cannot be reliably associated with an OIDC identity.
-- Keep them explicitly LEGACY. New OIDC provenance will be written only by
-- the authenticated approval path in a later step.

ALTER TABLE approval_request
    ADD COLUMN maker_identity_scheme text NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN maker_issuer text;

ALTER TABLE approval_request
    ADD CONSTRAINT ck_approval_maker_identity_provenance CHECK (
        (maker_identity_scheme = 'LEGACY' AND maker_issuer IS NULL)
        OR
        (maker_identity_scheme = 'OIDC'
            AND maker_issuer IS NOT NULL
            AND length(btrim(maker_issuer)) BETWEEN 1 AND 2048)
    );

-- Provenance is part of the original request and cannot be rewritten later.
CREATE FUNCTION guard_approval_identity_provenance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.maker_identity_scheme IS DISTINCT FROM OLD.maker_identity_scheme
            OR NEW.maker_issuer IS DISTINCT FROM OLD.maker_issuer THEN
        RAISE EXCEPTION 'approval maker identity provenance cannot change'
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_identity_provenance_guard
    BEFORE UPDATE ON approval_request
    FOR EACH ROW
    EXECUTE FUNCTION guard_approval_identity_provenance();