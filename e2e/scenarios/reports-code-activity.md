# Reports code activity — a team's merge-request flow over GitLab (the non-admin journey)

- **Spec**: [tests/reports-code-activity.spec.ts](../tests/reports-code-activity.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed and to read the report as the oracle; one throwaway REGULAR user
  (no roles) who does all the reading through the browser — D12, every signed-in user sees every report
- **Owns** (exclusive server-side state): its uniquely named GitLab connection (`e2e-code-*`), created and synced through the API and deleted at the end of
  the test; the identity decisions of the stub host `gitlab-stub:8080` (`public.code_identity_links`, keyed by host, so they outlive the connection): written
  only by this spec and `code-identities`, cleared at its start and again at its end; one throwaway team (`e2e-code-team-*`) with ONE linked stub person on its
  roster; and the throwaway reader. It reads the shared Jira fixture through `sharedFixtureReady` (that is what puts the stub's 30 Jira people in `norm.people`,
  which a roster membership needs) and never mutates it. It holds the `data-sources` lock (the stub host allows one active GitLab source, so it never runs beside
  the data-sources spec's GitLab test or `code-identities`), and sweeps leftover `e2e-identities-*`, `e2e-gitlab-*` and `e2e-code-*` sources at its start.

## Scenario: a regular user reads a team's code activity: the member's commits, the merge-request flow and the weekly bars equal the API, the member's commits open as the items behind them with GitLab links, the unlinked identities are told, a clean axe scan, and the Unassigned choice of the team control reads the work no team holds (teamId=0, kept on reload, the unlinked identity by its GitLab name without a link)

1. Through the API, leftover GitLab-stub sources of earlier runs are deleted, the shared Jira fixture is made ready, a GitLab-stub source (groups `platform` and
   `apps`) is created and synced, every stale decision of the stub host is deleted and **Confirm all proposals** is called (6 Jira people linked, 1 bot). A
   reader is created, and a throwaway team gets one linked stub person (the first of Sample User 9, 3, 5, 13, 4 and 1 whose membership is not refused with
   `409`) on its roster from 2025-01-01. The test reads `GET /api/v1/reports/code-activity` for the team over 2026-03-01 to 2026-09-30 (the whole commit window of
   the stub) as the oracle.
   - *Expected*: the person has a row, with commits above zero, and the sources list is exactly the spec's own source.
2. The reader signs in and opens `/reports/code-activity?teamId=<team>&from=2026-03-01&to=2026-09-30`.
   - *Expected*: the heading "Code activity" and four level-3 cards — "Merge request flow", "Merged per week", "Open merge requests now", "Activity by member"; the
     URL keeps the team and the period and carries no Jira slice or connection.
3. They read the flow.
   - *Expected*: the "Merged" tile shows the API's merged count; the five distributions (cycle time in working days and in elapsed days, time to first review, size in
     lines and in files) each have their level-5 title; when something was merged the weekly bar chart is drawn and its table "Merged per week, as a table" lists
     exactly the API's weeks with their counts, zero weeks included.
4. They read the members' table.
   - *Expected*: the person's row shows, under **Commits** and **Merged**, the API's commits and merged-merge-request counts; the neutral caption ("Context, not
     performance: these are volumes of work, in no order of merit…") is shown; no column header is a button (no sort, no ranking).
   - The test also reads the API's drill for the person (`GET /api/v1/reports/code-activity/items?…&accountId=<person>&view=COMMITS`): its `total` equals the commits figure.
   - They press the person's **Commits** figure (a button named "<n> — Commits: show the items of <name>").
   - *Expected*: a dialog "Commits · <name>" opens listing as many items as the figure (at most 500); the first key is a link to the stub's GitLab (`<baseUrl>/<project>/-/commit/<sha>`)
     opening in a new tab with `rel="noopener noreferrer"`; Escape closes the dialog and focus returns to the figure.
5. They read the sources and the note.
   - *Expected*: the source's line "<name>: last synced …" is shown; when the API reports undecided identities or any count of rows left out of a figure (lines or size
     unknown, details not read, undated acts), one gray note "Still to attribute, or not yet known" appears (stating the identities' count when there are any), and none
     appears otherwise; a regular user gets no "Open the identities of …" link.
6. They run an accessibility scan.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero violations.
7. They open the filters and pick **Unassigned**, the last choice of the Team control. The test has read `GET /api/v1/reports/code-activity?teamId=0` over the same window as the oracle: the stub's unmatched author (a
   personal e-mail the confirmed proposals do not link) is an undecided identity there, a row with a GitLab name, no account and commits above zero.
   - *Expected*: the URL carries `teamId=0` and keeps `from=2026-03-01&to=2026-09-30` (in whatever order the app writes them); the members' table has exactly one row named as GitLab names that identity, its **Commits** equal the API's, and its name is not a link; the
     hint "Unassigned: the work of GitLab identities that no administrator has linked to a person yet…" is shown; the "Merged" tile shows the API's `teamId=0` merged count; there is no Member control.
8. They reload the page.
   - *Expected*: the URL still says `teamId=0` with the same dates (it is not rewritten to the first team), the same row shows the same commits, and an axe scan reports zero violations.
9. Whatever the outcome, the test removes the membership, the team, the reader, every decision of the host and the connection.

## Not covered here (and why)

- Every card's states (withheld distributions, no review block, the open ages under the minimum sample, no active source, the USER level, the admin links, a failed load):
  `ReportCodeActivity.test.tsx` and `CodeMembersTable.test.tsx` (web); the Unassigned bucket's details (linked-but-teamless rows, drill targets, the members of a pasted link, the 400/429
  messages): `ReportCodeActivityUnassigned.test.tsx`.
- The attribution rule, the figures' definitions and the `400` matrix: `ReportCodeActivityTest`, `ReportCodeActivityFixtureTest` and the kernel tests (server).
- The identity decisions themselves: `code-identities.spec.ts`.
