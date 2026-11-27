CREATE TABLE app.import_job (
    store_id uuid NOT NULL REFERENCES app.store(id) ON DELETE RESTRICT,
    id uuid NOT NULL,
    actor_id uuid NOT NULL,
    kind text NOT NULL,
    supplier_id uuid,
    note text,
    revision integer NOT NULL DEFAULT 0,
    state text NOT NULL CHECK (state IN ('QUEUED','READY','SAVED','FAILED')),
    preview_token text,
    summary jsonb,
    edits jsonb NOT NULL DEFAULT '[]'::jsonb,
    result jsonb,
    operation_id uuid,
    save_operation_id uuid,
    expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days',
    PRIMARY KEY (store_id,id)
);
CREATE INDEX import_job_expiry_idx ON app.import_job (expires_at)
    WHERE state IN ('QUEUED','READY');
CREATE TABLE app.import_job_row (
    store_id uuid NOT NULL,
    job_id uuid NOT NULL,
    row_number integer NOT NULL,
    raw jsonb NOT NULL,
    preview jsonb,
    errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    PRIMARY KEY (store_id,job_id,row_number),
    FOREIGN KEY (store_id,job_id) REFERENCES app.import_job(store_id,id) ON DELETE RESTRICT
);
SELECT app.enable_tenant_rls('app.import_job');
SELECT app.enable_tenant_rls('app.import_job_row');
GRANT SELECT, INSERT, UPDATE ON app.import_job TO app_backend;
GRANT SELECT, INSERT, UPDATE ON app.import_job_row TO app_backend;

CREATE FUNCTION app.active_import_jobs() RETURNS integer
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = app, pg_temp AS $$
    SELECT count(*)::integer FROM app.import_job WHERE expires_at>now() AND state IN ('QUEUED','READY')
$$;
REVOKE ALL ON FUNCTION app.active_import_jobs() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.active_import_jobs() TO app_backend;

CREATE FUNCTION app.prune_import_job_rows() RETURNS integer
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = app, pg_temp AS $$
DECLARE removed integer;
BEGIN
    WITH failed AS (
        SELECT j.store_id,j.id,j.actor_id,r.problem FROM app.import_job j JOIN app.backoffice_write_request r
        ON (r.store_id,r.actor_id,r.operation_id)=(j.store_id,j.actor_id,j.operation_id)
        WHERE j.state='QUEUED' AND r.status='FAILED'
        LIMIT 32 FOR UPDATE OF j SKIP LOCKED
    ), finalized AS (
        UPDATE app.import_job j SET state='FAILED',result=f.problem,expires_at=now()+interval '24 hours'
        FROM failed f WHERE (j.store_id,j.id)=(f.store_id,f.id)
        RETURNING j.store_id,j.id,j.actor_id
    )
    UPDATE app.import_upload u SET status='RELEASING',expires_at=now()+interval '24 hours'
    FROM finalized f WHERE (u.store_id,u.id,u.actor_id)=(f.store_id,f.id,f.actor_id) AND u.status='READY';

    WITH targets AS (
        SELECT j.store_id,j.id FROM app.import_job j
        WHERE (j.expires_at<now() OR j.state IN ('SAVED','FAILED'))
          AND EXISTS (SELECT 1 FROM app.import_job_row r WHERE (r.store_id,r.job_id)=(j.store_id,j.id))
        ORDER BY j.expires_at LIMIT 16 FOR UPDATE SKIP LOCKED
    ), expired AS (
        SELECT r.store_id,r.job_id,r.row_number FROM targets j JOIN app.import_job_row r
        ON r.store_id=j.store_id AND r.job_id=j.id LIMIT 2000
    )
    DELETE FROM app.import_job_row r USING expired e
    WHERE (r.store_id,r.job_id,r.row_number)=(e.store_id,e.job_id,e.row_number);
    GET DIAGNOSTICS removed = ROW_COUNT;
    -- Keep the terminal tombstone to make a replay with a new HTTP key harmless.
    UPDATE app.import_job SET edits='[]',summary=NULL,result=NULL
    WHERE (store_id,id) IN (SELECT store_id,id FROM app.import_job
        WHERE expires_at<now()-interval '6 days' AND (summary IS NOT NULL OR result IS NOT NULL)
        LIMIT 100 FOR UPDATE SKIP LOCKED);
    RETURN removed;
END $$;
REVOKE ALL ON FUNCTION app.prune_import_job_rows() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app.prune_import_job_rows() TO app_backend;
