-- V47: the issue-key candidates of the code layer's COMMITS (gitlab-integration.md "Issue keys", persistence.md V47; A52). The twin of
-- V46: PROCESS reads each commit's TITLE (its first line; no body is ever stored) through the same token rule and stores EVERY candidate,
-- junk included, with no Jira knowledge; the reports scope and resolve at read time. Same idiom: schema-qualified, connection_id references
-- public.source_connections, NO foreign key to norm.code_commits; REPLACED per commit page with the commit (a commit is immutable by sha, so
-- only a rule change - a CODE_PROCESSING_VERSION bump - rewrites a row), drained by PURGE before the commits. Purely additive.
CREATE TABLE norm.code_commit_issue_keys (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    project_id BIGINT NOT NULL,
    sha VARCHAR(64) NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    PRIMARY KEY (connection_id, project_id, sha, issue_key)
);
-- Task -> commits: which commits of a connection name a key (the commit side is filtered by joining code_commits for live rows).
CREATE INDEX idx_norm_code_commit_issue_keys_key ON norm.code_commit_issue_keys (connection_id, issue_key);
