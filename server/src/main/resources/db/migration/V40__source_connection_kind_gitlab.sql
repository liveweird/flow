-- V40: a second connector kind (GitLab, `.claude/docs/gitlab-integration.md` "The connector seam"). V8 declared
-- `source_connections.kind` with an inline, unnamed CHECK (kind IN ('JIRA_CLOUD')), which PostgreSQL named
-- `source_connections_kind_check`; widen it to admit 'GITLAB' (V16's constraint-swap pattern for `sync_jobs.kind`).
-- Schema-only: no row is rewritten, every existing row is JIRA_CLOUD and still satisfies the wider check, and an old pod
-- (which only knows JIRA_CLOUD) never reads a GITLAB row because nothing creates one before the GitLab data-source release.
ALTER TABLE source_connections DROP CONSTRAINT source_connections_kind_check;
ALTER TABLE source_connections ADD CONSTRAINT source_connections_kind_check CHECK (kind IN ('JIRA_CLOUD', 'GITLAB'));
