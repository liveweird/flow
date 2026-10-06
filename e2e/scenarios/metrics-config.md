# Metrics configuration (v0.3.0 M4 commit 14, batch 1)

- **Spec**: [tests/metrics-config.spec.ts](../tests/metrics-config.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`); a throwaway regular user in the third
  test
- **Owns** (exclusive server-side state): its own edits to the GLOBAL `metrics.settings` singleton
  — captured through the API before the first test's edits and restored through the API at the
  end of that same test, so the shared settings a real admin might also be reading are never left
  changed; a throwaway Jira-stub data source per test that needs one (unique `e2e-metrics-ds-*`
  names) and a throwaway team per test that needs one (unique `e2e-metrics-team-*` names), all
  deleted by the end of their own test; a throwaway user in the third test. The second and third
  tests each sync their OWN connection rather than sharing one — see "Not covered here".

## Scenario: admin adjusts metrics settings

1. The admin opens **Metrics settings**.
   - *Expected*: the form loads pre-filled with the current global values.
2. They raise the **Aging-WIP window (items)** value and add a new date to **Holidays**, then
   save.
   - *Expected*: a "Saved — reports re-derive shortly" confirmation.
3. They reload the page.
   - *Expected*: both changes are still there — the save persisted.
4. They mark every day of the week (Mon..Sun) as a weekend day and save again.
   - *Expected*: an inline "Weekend days must not mark every day of the week as non-working"
     error — refused before any request is sent, so the persisted settings are untouched.
5. The test restores the settings singleton to its pre-test values through the API (this page
   edits a value every admin shares, so a real admin's own configuration is never left disturbed
   by a test run).

## Scenario: admin configures a data source's metrics

1. The admin creates a throwaway Jira-stub data source through the New data source modal (the
   data-sources spec's own creation steps, minus its Test-connection detour — that surface is
   already covered there, and a successful Sync below proves reachability just as well).
2. They open its details page and click **Sync now**, then wait (reloading) until the Sync job
   reads Succeeded.
3. They create a throwaway team, then open the data source's **Metrics configuration** page.
   - *Expected*: the "Showing computed defaults — save to confirm them" banner is visible, and
     the `To Do` status's Stage field already reads "Not started" (preselected from Jira's own
     status category).
4. Under the stage table, in the **Per-domain overrides** section, they choose the `PLT` domain
   and set the `To Do` status's stage in that domain to Done.
   - *Expected*: its field reads "Done" (it started on "Same as all domains") and the section
     says "Overrides in total: 1"; the every-domain `To Do` field still reads "Not started".
5. On the Boards tab, they map the `Flow Core board` to the throwaway team and save (this one
   save carries the override too).
   - *Expected*: a "Saved — reports re-derive shortly" confirmation; after a reload, the
     not-configured banner is gone, the section still says "Overrides in total: 1", and the `PLT`
     domain's `To Do` field still reads "Done".
6. They map the `Platform board` to the SAME throwaway team and save again.
   - *Expected*: an inline "This team is already mapped to another board" error, on the Platform
     board's Team field specifically (D10: one board per team).
7. The test deletes the throwaway team and the data source.

## Scenario: admin adds a dated Jira member to a team; a regular user sees it read-only

1. The test syncs its own throwaway Jira-stub data source through the API (fast — the UI
   creation/sync flow is the previous test's job) so the site's Jira-user roster exists to pick
   from, and creates a throwaway team and a throwaway regular user.
2. The admin opens the team and adds a Jira member (`Sample User 1` from the stub's deterministic
   roster) with a closed valid-from/valid-to date range in the past.
   - *Expected*: the new row appears with both dates.
3. They try to add the SAME person again with an overlapping date range.
   - *Expected*: an inline "This person already has a membership covering that period…" error in
     the modal — never a toast, and no second row is created.
4. The admin signs out; the throwaway regular user signs in.
   - *Expected*: there is no **Metrics settings** nav link, and visiting `/metrics-settings`
     directly redirects to Home.
5. The user opens the same team.
   - *Expected*: the Jira member row is visible (read-only) with no **Add Jira member** button and
     no per-row operations control.
6. The admin signs back in and deletes the throwaway user, team and data source.

## Not covered here (and why)

- **The reports half of v0.3.0** — this file covers configuration only; the report pages are read by
  `reports.spec.ts` ([reports.md](reports.md)).
- **Sharing one synced connection between the second and third tests.** Each test syncs its own
  throwaway connection instead, trading a second ~1-2 minute stub sync for full per-test
  independence (no test relies on another test's connection surviving or running first) — the
  same "each spec/test owns its own state" rule the rest of the suite follows.
- **Every metrics-config tab** (Fields, Domains, Activity types, Work categories, Capacities) and
  every settings field — pinned by the server's `MetricsConfigRoutesTest`/`MetricsSettingsRoutesTest`
  and the SPA's `DataSourceMetricsConfig.test.tsx`/`MetricsSettings.test.tsx`; the journey drives
  the Boards/Statuses tabs and a couple of settings fields as its representative path.
- **Ending a membership / removing a membership row** — pinned by `TeamMembershipRoutesTest` and
  `TeamJiraMembers.test.tsx`; the journey drives the add + overlap-conflict path.
