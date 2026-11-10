CREATE TABLE config_version
(
    version bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_config_version_actor CHECK (length(btrim(actor)) BETWEEN 1 AND 100)
);

CREATE TABLE config_approval_threshold
(
    config_version bigint NOT NULL REFERENCES config_version(version),
    currency char(3) NOT NULL,
    threshold_minor bigint NOT NULL,
    PRIMARY KEY (config_version, currency),
    CONSTRAINT ck_config_threshold_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_config_threshold_amount CHECK (threshold_minor >= 0)
);

CREATE FUNCTION reject_config_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'configuration history is append-only'
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_config_version_no_change
    BEFORE UPDATE OR DELETE OR TRUNCATE ON config_version
    FOR EACH STATEMENT EXECUTE FUNCTION reject_config_mutation();

CREATE TRIGGER trg_config_threshold_no_change
    BEFORE UPDATE OR DELETE OR TRUNCATE ON config_approval_threshold
    FOR EACH STATEMENT EXECUTE FUNCTION reject_config_mutation();