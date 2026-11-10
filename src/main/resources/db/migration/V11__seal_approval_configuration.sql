CREATE TABLE config_version_seal
(
    config_version bigint PRIMARY KEY REFERENCES config_version(version)
);

-- Versions created under V10 are complete at migration time.
INSERT INTO config_version_seal (config_version)
SELECT version FROM config_version;

CREATE FUNCTION guard_config_threshold_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Serialize against sealing for this version.
    PERFORM version FROM config_version WHERE version = NEW.config_version FOR UPDATE;
    IF EXISTS (SELECT 1 FROM config_version_seal WHERE config_version = NEW.config_version) THEN
        RAISE EXCEPTION 'configuration version is sealed' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION lock_config_before_seal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM version FROM config_version WHERE version = NEW.config_version FOR UPDATE;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_config_threshold_insert_guard
    BEFORE INSERT ON config_approval_threshold
    FOR EACH ROW EXECUTE FUNCTION guard_config_threshold_insert();

CREATE TRIGGER trg_config_seal_lock
    BEFORE INSERT ON config_version_seal
    FOR EACH ROW EXECUTE FUNCTION lock_config_before_seal();

CREATE TRIGGER trg_config_seal_no_change
    BEFORE UPDATE OR DELETE OR TRUNCATE ON config_version_seal
    FOR EACH STATEMENT EXECUTE FUNCTION reject_config_mutation();