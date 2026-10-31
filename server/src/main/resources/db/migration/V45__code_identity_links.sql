-- V45: the identity links (gitlab-integration.md "Identity", PR 11): an ADMIN's decision about ONE GitLab identity of ONE instance - this
-- person-key is that Jira person (LINKED), is nobody we attribute (IGNORED), or is a bot (BOT). A decision is the only thing stored: the
-- proposals (an address that matches a Jira person) are computed at read time against norm.people and never written, so an unconfirmed
-- match attributes nothing. Persistence side (key, CHECKs, readers and writers): persistence.md "Code identity links (V45)".
--
-- Why the key is (source_host, identity_key) and there is NO connection foreign key. identity_key is the code layer's "u<gitlab user id>" /
-- "e<first 32 hex of sha256(canonical email)>" (norm.code_identities, V44), which is stable per GitLab instance; source_host is that
-- instance's authority (lowercase host, plus ":port" when it is not the scheme's default), taken from the data source's canonical base URL.
-- norm.code_identities is rebuilt wholesale on every PROCESS run and drained by PURGE, and a data source can be deleted and created again
-- for the same instance: a decision must outlive all three, so it points at the instance, never at a row or a connection. A decision whose
-- identity is gone stays listed (as not seen) on the decided tabs of any active data source on the same host and is removable there; with no
-- active source on the host it waits, inert, until one is created.
--
-- Why HARD delete. The deletable action is "this decision was wrong"; there is no history the table must keep (the audit trail carries
-- who decided and who removed what), and a removed decision is simply an identity with no decision again - the metrics.team_membership
-- justification (persistence.md "Soft delete (convention)"). Undated: a link has no valid-from/to, it holds until changed or removed.
--
-- Why the CHECKs. A decision is a closed vocabulary the SQL itself must guarantee, whatever writes it: decision is one of three values, and
-- jira_account_id is non-null EXACTLY when the decision is LINKED, and never empty - a LINKED row without an account (it would attribute
-- nothing and answer the resolver with a hole) and an IGNORED/BOT row that still names one (a stale account that a later edit could
-- resurrect) are impossible. The key and the host are closed shapes too: identity_key is "u<digits>" or "e<32 hex>" (what the code layer
-- mints), source_host is non-empty and lowercase (what sourceHostOf mints), so a typo can never be a second row for the same identity.
-- jira_account_id is VARCHAR(100), norm.people.account_id's width. It has no foreign key: norm.people rows are per connection and rebuilt,
-- so no row of it is a stable target; the service checks the account is known when it writes. If a LINKED account later vanishes from
-- Jira the link stays and still resolves (the list shows it without a display name), and a re-PUT of the same account is accepted.
--
-- decided_by_user_id references users(id) and is NOT NULL because every decision is made by an admin: users are only ever soft-deleted
-- (flowusers/), never removed, so the reference holds for good, and there is deliberately no ON DELETE rule - an admin's departure must
-- never silently unlink people. Purely additive: an old pod never reads this table.
CREATE TABLE code_identity_links (
    source_host VARCHAR(300) NOT NULL,
    identity_key VARCHAR(40) NOT NULL,
    decision VARCHAR(10) NOT NULL,
    jira_account_id VARCHAR(100),
    decided_by_user_id BIGINT NOT NULL REFERENCES users(id),
    decided_at BIGINT NOT NULL,
    PRIMARY KEY (source_host, identity_key),
    CONSTRAINT ck_code_identity_links_decision CHECK (decision IN ('LINKED', 'IGNORED', 'BOT')),
    CONSTRAINT ck_code_identity_links_key CHECK (identity_key ~ '^(u[0-9]{1,19}|e[0-9a-f]{32})$'),
    CONSTRAINT ck_code_identity_links_host CHECK (source_host <> '' AND source_host = lower(source_host)),
    CONSTRAINT ck_code_identity_links_account CHECK (
        (decision = 'LINKED') = (jira_account_id IS NOT NULL) AND (jira_account_id IS NULL OR jira_account_id <> '')
    )
);

-- The resolver's keysByAccount (a person -> the identity keys linked to them on a host): it filters on host, LINKED and the account.
CREATE INDEX idx_code_identity_links_account ON code_identity_links (source_host, jira_account_id) WHERE decision = 'LINKED';
