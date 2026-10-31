-- V45: the identity links (gitlab-integration.md "Identity", PR 11): an ADMIN's decision about ONE GitLab identity of ONE instance - this
-- person-key is that Jira person (LINKED), is nobody we attribute (IGNORED), or is a bot (BOT). A decision is the only thing stored: the
-- proposals (an address that matches a Jira person) are computed at read time against norm.people and never written, so an unconfirmed
-- match attributes nothing. Persistence side (key, CHECKs, readers and writers): persistence.md "Code identity links (V45)".
--
-- Why the key is (source_host, identity_key) and there is NO connection foreign key. identity_key is the code layer's "u<gitlab user id>" /
-- "e<first 32 hex of sha256(canonical email)>" (norm.code_identities, V44), which is stable per GitLab instance; source_host is that
-- instance's authority (lowercase host, plus ":port" when it is not the scheme's default), taken from the data source's canonical base URL.
-- norm.code_identities is rebuilt wholesale on every PROCESS run and drained by PURGE, and a data source can be deleted and created again
-- for the same instance: a decision must outlive all three, so it points at the instance, never at a row or a connection. A link whose
-- identity never comes back is a harmless orphan an ADMIN can remove.
--
-- Why HARD delete. The deletable action is "this decision was wrong"; there is no history the table must keep (the audit trail carries
-- who decided and who removed what), and a removed decision is simply an identity with no decision again - the metrics.team_membership
-- justification (persistence.md "Soft delete (convention)"). Undated: a link has no valid-from/to, it holds until changed or removed.
--
-- Why the CHECKs. A decision is a closed vocabulary the SQL itself must guarantee, whatever writes it: decision is one of three values, and
-- jira_account_id is non-null EXACTLY when the decision is LINKED - a LINKED row without an account (it would attribute nothing and answer
-- the resolver with a hole) and an IGNORED/BOT row that still names one (a stale account that a later edit could resurrect) are impossible.
-- jira_account_id is VARCHAR(100), norm.people.account_id's width. It has no foreign key: norm.people rows are per connection and rebuilt,
-- so no row of it is a stable target; the route checks the account is known when it writes.
--
-- decided_by_user_id references users(id): users are only ever soft-deleted (flowusers/), never removed, so the reference holds for good,
-- and there is deliberately no ON DELETE rule - an admin's departure must never silently unlink people. Purely additive: an old pod never
-- reads this table.
CREATE TABLE code_identity_links (
    source_host VARCHAR(255) NOT NULL,
    identity_key VARCHAR(40) NOT NULL,
    decision VARCHAR(10) NOT NULL,
    jira_account_id VARCHAR(100),
    decided_by_user_id BIGINT NOT NULL REFERENCES users(id),
    decided_at BIGINT NOT NULL,
    PRIMARY KEY (source_host, identity_key),
    CONSTRAINT ck_code_identity_links_decision CHECK (decision IN ('LINKED', 'IGNORED', 'BOT')),
    CONSTRAINT ck_code_identity_links_account CHECK ((decision = 'LINKED') = (jira_account_id IS NOT NULL))
);

-- The resolver's keysByAccount (a person -> the identity keys linked to them on a host): LINKED rows only carry an account.
CREATE INDEX idx_code_identity_links_account ON code_identity_links (jira_account_id) WHERE jira_account_id IS NOT NULL;
