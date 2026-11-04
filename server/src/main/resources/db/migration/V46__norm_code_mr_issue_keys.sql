-- V46: the issue-key candidates of the code layer's merge requests (gitlab-integration.md "Issue keys", persistence.md V46). PROCESS reads
-- each merge request's title and source branch (norm/IssueKeys.kt: a token like FLO-12, folded to uppercase) and stores EVERY candidate
-- the rule admits, with no knowledge of which Jira projects exist - the reports scope a candidate to the known project keys at read time
-- and resolve it to a Jira issue through norm.work_items (below). A candidate such as UTF-8 or SHA-256 is one small row that never surfaces.
-- Same idiom as V44: schema-qualified, connection_id references public.source_connections, and NO foreign key to norm.code_merge_requests
-- (V13/V44 have none between norm tables): the rows are REPLACED per merge request on every PROCESS of it (deleted, then inserted, like
-- norm.code_mr_events) and drained by PURGE before the merge requests. Only merge requests carry keys (commits are linked through their MR).
-- Purely additive: an old pod never reads this table.

-- One row per (merge request, candidate key). in_title / in_branch say where the key was found (at least one is true; a key in both is ONE
-- row with both). issue_key is the uppercase key, VARCHAR(20) like norm.work_items.issue_key.
CREATE TABLE norm.code_mr_issue_keys (
    connection_id INTEGER NOT NULL REFERENCES public.source_connections(id),
    mr_id BIGINT NOT NULL,
    issue_key VARCHAR(20) NOT NULL,
    in_title BOOLEAN NOT NULL,
    in_branch BOOLEAN NOT NULL,
    PRIMARY KEY (connection_id, mr_id, issue_key)
);
-- Task -> merge requests: which merge requests of a connection name a key (the merge request side is filtered by joining
-- code_merge_requests for live rows).
CREATE INDEX idx_norm_code_mr_issue_keys_key ON norm.code_mr_issue_keys (connection_id, issue_key);

-- The key -> issue resolution of the reports: norm.work_items had no index on issue_key (its primary key is (connection_id, issue_id)).
CREATE INDEX idx_norm_work_items_issue_key ON norm.work_items (connection_id, issue_key);
