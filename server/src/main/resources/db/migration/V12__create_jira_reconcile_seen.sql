-- The RECONCILE stream's scratch id-sweep table (v0.2.0 plan §4/§7/§12 item 7, in the `raw` schema
-- created by V10): raw.jira_reconcile_seen holds every issue id the daily id-sweep
-- (`search/jql fields=id`, jira/JiraReconcileStream.kt) saw during ONE reconcile pass, keyed by the
-- sync_jobs row driving that pass — a resumed pass (same job_id, after a lease loss/reclaim)
-- re-inserts idempotently (ON CONFLICT DO NOTHING, jira/JiraRawStore.kt's insertReconcileSeen)
-- rather than duplicating, and a stale/interrupted pass's rows never collide with a LATER pass's
-- own job_id. `job_id` is a plain column, not a foreign key: this table is cleared in full
-- (JiraRawStore.clearReconcileSeen) once its pass's own anti-join step completes, and pinning it to
-- sync_jobs.id would let a lingering scratch row (an interrupted pass that never reached cleanup)
-- block that job row's own eventual hard-delete prune (ingest/SyncJobsService.kt's `prune`,
-- `.claude/docs/persistence.md` "The sync_jobs prune hard-delete exception").
CREATE TABLE raw.jira_reconcile_seen (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    job_id INTEGER NOT NULL,
    issue_id BIGINT NOT NULL,
    PRIMARY KEY (connection_id, job_id, issue_id)
);
