# Code identities (the GitLab identity mapping, 1.38.0)

- **Spec**: [tests/code-identities.spec.ts](../tests/code-identities.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`)
- **Owns** (exclusive server-side state): its uniquely named GitLab connection (`e2e-identities-*`), created and synced through the API
  and deleted at the end of its test, AND the identity decisions of the stub host `gitlab-stub:8080`
  (`public.code_identity_links`): the only spec that writes them, cleared at its start and again at its end (a decision is keyed by
  host, so it would outlive the connection and show on the next run). It reads the shared Jira fixture through `sharedFixtureReady`
  (that is what puts the stub's 30 Jira people in `norm.people`) and never mutates it. It holds the `data-sources` lock (the
  data-sources spec's GitLab test creates a source on the same stub host with the same groups, which the server refuses with a 409
  while the other is active), and sweeps leftover `e2e-identities-*` / `e2e-gitlab-*` sources at its start so a crashed run cannot
  break the next. A GitLab connection changes no derive input.

## Scenario: admin reviews the GitLab stub's identities: confirms the proposals, ignores the service account, links the unmatched author and removes a decision

1. Through the API, leftover GitLab-stub sources of earlier runs are deleted, the shared Jira fixture is made ready, a GitLab-stub source (groups `platform` and `apps`) is created and synced, and
   every stale decision of the stub host is deleted.
2. The admin signs in, opens the source's details page and clicks **Identities**.
   - *Expected*: the URL ends in `/identities`, the heading reads **Identities** and the **Proposed** tab is selected.
3. They read the Proposed table.
   - *Expected*: seven rows (`sample-data/gitlab/expected.json` `users.jiraAlignment.matches`): GitLab users `sample.dev1`, `sample.dev4`,
     `sample.dev3`, `sample.dev5`, `sample.dev9` and `sample.dev13` each proposed to the Jira stub's person **Sample User 1 / 4 / 3 / 5 /
     9 / 13** (`sample.dev3` only through its confirmed secondary address; its primary address `sample.dev3@mail.example` is shown),
     and the bot `project_3_bot_4f1a9c2e7b` proposed as **Bot**.
4. They click **Confirm all proposals (7)** and confirm in the dialog.
   - *Expected*: the toast reads "6 linked, 1 marked as bots"; the Proposed tab shows its empty state; **Linked** lists 6 rows and
     **Ignored & bots** lists 1 (the bot).
5. They open **Unmapped**.
   - *Expected*: two rows (`users.list` / `users.unmatchedAuthors`): the admin service account `flow-sync`, which matches no Jira person,
     and the commit author **Sample Contractor** with no GitLab account, whose address `contractor.sample@mail.example` is shown in full.
6. On the service account's row they open the operations menu and choose **Ignore**.
   - *Expected*: a toast "Ignored"; Unmapped keeps 1 row and **Ignored & bots** lists 2.
7. On the contractor's row they choose **Link to a Jira person…**, type "Sample User 2" in the picker, pick that person and **Save**.
   - *Expected*: a toast "Linked", the dialog closes, **Linked** lists 7 rows and the contractor's row names **Sample User 2**.
8. On the contractor's row they choose **Remove decision** and confirm.
   - *Expected*: a toast "Decision removed"; **Linked** lists 6 rows again and the contractor is back in **Unmapped**.
9. Whatever the outcome, the test deletes every decision of the host and the connection.

## Not covered here (and why)

- The 403/404/409 matrix, the audit events, the idempotent re-PUT and the survival of a decision across a re-created source:
  `CodeIdentityRoutesTest` (server).
- The proposal rule (email match, ambiguity, bot precedence): `CodeIdentityProposalsTest`; the resolver the later reports read:
  `IdentityResolverTest`.
- Orphaned decisions ("Not seen") and the Linked/Ignored tabs' paging and search: `DataSourceIdentities.test.tsx`.
