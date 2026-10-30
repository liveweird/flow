# Frontend feature pages

Read on demand, in full, before touching a data-source, metrics-config or report page in `web/`; the conventions that apply to every page stay in `web/CLAUDE.md`.

## Data sources (`pages/DataSources|DataSourceDetails|DataSourceProfile|RawIssueInspector.tsx`)

The first Jira-domain surface (v0.2.0 plan §9/§10), ADMIN-only end to end: the nav leaf
(`IconPlugConnected`, `adminOnly`), the four routes (`/data-sources`, `/data-sources/:id`,
`…/:id/profile`, `…/:id/inspect`, all under the same `RequireAdmin` group as Flow users/Feature flags
in `App.tsx`; every page's queries additionally stay `enabled` only for `useAdmin()`), and every
endpoint (`requireAdmin` server-side).

- **`api/dataSources.ts`** mirrors `api/teams.ts`'s shape — list/create/update/delete plus the two
  Test-connection wrappers (`testDataSourceAdHoc`/`testDataSourceStored`) and `requestSyncJob`
  and the per-connection reads/actions: `listSyncJobs`/`cancelSyncJob` (`…/sync-jobs`),
  `getDataSourceStatus` (`…/status`), `getRawIssue` (`…/raw-issues/{issueKey}`) and
  `getDataSourceProfile` (`…/profile`) — every type derived from `schema.ts`. Unlike Teams, `DataSourcePage.items` already carries the FULL `DataSourceResponse`
  (minus the write-only token) — Edit opens straight from the row, no extra detail fetch.
- **The pauses (`components/DataSourcePause.tsx`, 1.21.0; RECONCILE's 1.27.0).** Two independent ceiling-trip pauses (`ingestion.md` "Ceiling trips and the pause"),
  each shown in ORANGE (the attention colour, not the blocking red) and both at once when both are on. SYNC's (`status.pausedAt`/`pauseReason`/`limitTrips`)
  is an orange `Paused` badge beside the state badge (its `title` is the reason) on the list and the details page; RECONCILE's
  (`status.reconcilePausedAt`/`reconcilePauseReason`/`reconcileLimitTrips`, V38) is a separate `Reconcile paused` badge beside it. The details page adds one
  orange `Alert` per active pause above the connection table (`PauseAlert`, keys `dataSources.paused.*` / `dataSources.reconcilePaused.*`): since when
  (`formatEpochMillis`), the reason (`dataSources.paused.reason.<code>`, shared, `OTHER` for a code this build does not know), the count of runs in a
  row, and how to resume - SYNC: "Sync now" or saving the connection; RECONCILE: "Reconcile now" or saving, scheduled syncs keep running. Nothing
  renders for a pause whose `…PausedAt` is null (a count below the threshold is not shown). The `state` badge itself is unchanged (`FAILED`); there is no resume
  button, the existing Sync now (list row menu, details page) and Reconcile now (details page) are the resumes, and the details page's job `refresh()` re-reads the status
  so the alert disappears. Tests: `DataSourcePause.test.tsx` (SYNC alone, RECONCILE alone, both, neither), `DataSourceDetailsPause.test.tsx` (incl. the
  Reconcile-now refresh), one case per pause in `DataSources.test.tsx`; the telemetry Sync table (`TelemetrySyncSection`) adds the same badges from
  `freshness.pausedAt` / `freshness.reconcilePausedAt` (`TelemetryPanel.test.tsx`).
- **`pages/DataSources.tsx`** is the Teams registry template (`useRegistryListControls` +
  `RegistryListTable`), sorted by name only (the server's other sortable fields — `id`,
  `createdAt`, `updatedAt` — have no visible column). Columns: name, site host (parsed from
  `jira.siteUrl` — always a bare origin, so `new URL(...).host` is safe), projects (joined
  keys), enabled (plain Yes/No text — the state badge below is the one place this page spends
  colour), last success (`YYYY-MM-DD HH:mm` sliced from the ISO string like `VersionStamp`, never
  `toLocaleString()` — deterministic across test/CI locales; "Never" when null), and the
  `DataSourceState` badge — teal `CURRENT`, red `FAILED`, gray everything else
  (`NEVER_SYNCED`/`STALE`/`DISABLED`), the app's existing success/blocking/neutral vocabulary, no
  new hue. Row actions (`RowActionsMenu`): **Sync now** (`POST …/sync-jobs {kind: SYNC}`, a direct
  action with no confirm step — the success toast itself distinguishes a freshly queued job from
  `coalesced: true`; a failure renders inline above the table, same rule as everywhere else:
  never a toast), Edit, Delete (`ConfirmDeleteModal`, the same conflict-naming pattern as Teams).
- **`components/DataSourceEditorModal.tsx`** ports Covenant's `ToadieConnectionEditorModal` (the
  site URL disables once a connection exists — Toadie's `baseUrl` pattern — since a changed
  `siteUrl` is a `409` server-side). A save `409` marks the field its problem `type` names
  (`ApiError.type`, `dataSourceConflictField` in `utils/dataSourceForm.ts`), in fixed words, never the server's `detail`:
  `about:blank` (the case-insensitive name clash) marks `name` like Teams does,
  `urn:flow:problem:data-source-other-site` (create only: every data source must use one Jira site) marks `siteUrl`, and
  `urn:flow:problem:data-source-project-overlap` (a project key another active data source already holds) marks `projectKeys`. Fields: name, site URL (`https://<tenant>
  .atlassian.net` hint), service-account email, a `PasswordInput` token ("leave blank to keep the
  current token" on edit — blank travels as an omitted `apiToken`, which the server keeps), a
  `TagsInput` for project keys (force-uppercased in `onChange`, after `form.getInputProps`, so the
  wire value always matches `PROJECT_KEY_PATTERN`), a plain backfill-date `TextInput`
  (`YYYY-MM-DD`, blank omits the field so the server computes its default - 24 months back, raised to the
  history horizon's first day when that is later - no `@mantine/dates`), sync interval / reconcile hour `NumberInput`s, an auth-scheme `Select`
  (Basic/Bearer), and an enabled `Switch`. **`utils/dataSourceForm.ts`** mirrors
  `ingest/DataSource.kt`'s validation exactly (name/site-URL/email/token length caps, the Jira
  site-URL and project-key regexes plus the Atlassian-reserved-label rejection, the
  backfill-date range) — keep the two in sync. **The history-horizon floor (A48):** the modal reads
  the metrics settings (`getMetricsSettings`, query key `["metrics-settings"]`, ADMIN-only like the page) for `historyHorizonFrom`;
  `backfillFloorIso(horizonFrom)` is the later of the legacy 10-year floor and that day, the `backfillFrom` rule refuses an earlier
  date (`dataSources.validation.backfillFromBeforeHorizon`) UNLESS editing and the value equals the stored one (the server checks only
  a moved date; the legacy floor still applies to it), and the field's description appends the hint
  `backfillFromHorizonHint` with the floor. A failed or pending settings read means no hint and no client check - the server's `400`
  still lands in the save-error alert.
- **Test connection**: the same button drives both the ad-hoc probe (`POST /data-sources/test`,
  the form's current values — required whenever creating or rotating the token) and the stored
  probe (`POST /data-sources/{id}/test`, no body) when editing with a blank token field; it
  validates only the four Jira fields the probe needs (not the whole form) before calling.
  **`components/ConnectionTestResults.tsx`** renders the row table (endpoint + path / required /
  a teal-or-red result badge / a detail cell combining the upstream status, the
  `JiraFetchException` code and a failed row's `scopeHint`) and the resolved `cloudId` once
  `tenant_info` succeeds.
- **`pages/DataSourceDetails.tsx`** (`/data-sources/:id`, reached from the list's name link or its
  Open row action) is the connection's operational view over ONE `GET …/status` query: summary
  (state badge via `utils/dataSourceState.ts`), the current job (`JobStateBadge` + stream +
  progress counters), `components/CursorTable.tsx` (each cursor's `position` rendered verbatim —
  the server owns its shape) and the raw-store counts, plus the paged sync-jobs history
  (`components/SyncJobsTable.tsx` on the `RegistryListTable` shell, kind/status filters, a Cancel
  per still-open row; the error cell shows `errorCode` with a non-empty `errorDetail` beneath it — dimmed `xs` plain text, clamped to two lines, full text in `title`). **Auto-refresh is conditional**: the status query's `refetchInterval` is 5s
  only while `currentJob` is open, `false` otherwise — never a fixed poll (the server's `currentJob` is the open
  job — RUNNING, else the PENDING one the worker would claim first, `SyncJobsService.openJob` — so a just-requested job shows its badge and is
  polled from the first fetch). The sync-jobs history query polls on that condition
  (`jobOpen`) AND while any visible history row is PENDING/RUNNING, so a row that is not the summary's job (a second
  PENDING one) is still followed to Running and on to its terminal state; it also refetches once more when `jobOpen` falls
  (a ref-tracked open→idle transition), so its last row shows the terminal state even if the tick that raced the
  summary read it as Running. `keepPreviousData`/paging are untouched (the interval refetches the current key).
  A PENDING job whose `notBefore` is set (the debounced re-derive a configuration edit caused, `ingestion.md` "Debounced DERIVE") shows
  `components/JobScheduledNote.tsx` under its gray Pending badge — "Waiting for further changes — starts after <UTC time>" — in both the
  current-job card and the jobs table: scheduled, never a failure; the open-job poll above follows it to Running. The "Saved — reports
  re-derive shortly" toasts stay as they are (the wait is a quiet period of under a minute).
  Header actions
  Sync now / Reconcile (direct, the toast distinguishes `coalesced`), Reprocess (behind
  `ConfirmActionModal` — it rebuilds every normalized row), Cancel on the open job (direct; a `409`
  means it finished meanwhile and renders inline, never a toast), Edit (the same
  `DataSourceEditorModal`), and links to the profile and the inspector. A `404` or load failure goes
  through `EditPageLoadState` with a back link, like the user editors.
- **`pages/DataSourceProfile.tsx`** (`…/:id/profile`) renders `GET …/profile` as plain Mantine
  tables, one per section (projects, workflows with observed-vs-reference statuses, boards with
  unmapped statuses, custom fields with fill rate/role, estimates, worklogs, reopens, sprints,
  people, anomaly counts) — deliberately no charts until phase 3's dashboards. `computedAt: null`
  (no PROCESS pass yet) is an `EmptyState`, not an error; percentages print with one decimal,
  matching the server's rounding.
- **`pages/RawIssueInspector.tsx`** (`…/:id/inspect?key=`) — the looked-up key lives in the URL
  (`useSearchParams`), so a lookup is a shareable deep link (`dataSourceInspectPath(id, key)`); the
  query only runs once a key is present. Shows the canonical raw payload (the trimmed document: no comment or description text), tombstones, changelog/
  worklog payloads, and — once processed — the `norm.*` work item, its status/field intervals and
  anomaly badges (orange, the fixed `TilingAnomaly` vocabulary). A `400`/`404`/`413` renders INLINE under
  the form, never replacing the page, so the admin can simply try another key (`413`, `dataSources.inspect.tooLarge`: the issue's stored
  text is over `jira.processPageBytes`, the server answers before reading it; `saveErrorMessage`'s `tooLarge` key).
- **`utils/dataSourceLinks.ts`** is the ONE place the route family is spelled out
  (`dataSourcesPath`, `dataSourcePath`, `dataSourceProfilePath`, `dataSourceInspectPath`) — never
  hand-assemble these URLs. `utils/dataSourceState.ts` holds the state→colour map and
  `formatEpochMillis` (the deterministic `YYYY-MM-DD HH:mm` rendering, "Never" for null).
- `pages/Home.tsx`'s admin empty state links to `/data-sources` and `/metrics-settings` (plain `Anchor`s
  under the `EmptyState`, not a rewrite of that shared component) — see "Home overview" under Reports.

## Metrics configuration (`pages/MetricsSettings.tsx`, `components/TeamJiraMembers.tsx`, `pages/DataSourceMetricsConfig.tsx`)

The v0.3.0 metrics-configuration surfaces: the global settings singleton and D1's dated Jira-user
team membership landed in M2 commit 5, and the per-connection `metrics-config` page in commit 6.
`api/metrics.ts` mirrors `api/teams.ts`'s thin-wrapper shape: `getMetricsSettings`/
`updateMetricsSettings`, the `/jira-memberships` CRUD, the public-holiday dictionary (`listPublicHolidays`/`createPublicHoliday`/`deletePublicHoliday`), `listJiraUsers`, and
`getDataSourceMetricsConfig`/`updateDataSourceMetricsConfig`/`getDataSourceMetricsConfigOptions`
— every type derived from `schema.ts`.

- **`pages/MetricsSettings.tsx`** (`/metrics-settings`, `adminOnly` nav leaf `IconAdjustments`,
  under the same `RequireAdmin` route group as Flow users/Data sources) is the ONE global
  `metrics.settings` singleton form — the CreateFlowUser/EditFlowUser template (`Paper withBorder p="xl"
  maw={FORM_MAX_WIDTH}`, `form.initialize` guarded by `!form.initialized`, a full-replace PUT,
  `showSuccessToast` on save, an inline Alert on failure). `utils/metricsForm.ts` mirrors
  `metrics/MetricsSettings.kt`'s `validateMetricsSettings` field for field (including the
  aging-percentiles-must-include-85 rule and the all-weekend rejection; there is NO holidays field any more — the holiday list is the
  Public holidays dictionary below, and the form shows a one-line note with an "Open Public holidays" link where the field used to be) and holds the form's own
  string-array reshaping: `weekendDays`/`agingPercentiles` travel as `Chip.Group`/`TagsInput`
  string values client-side, mapped to `number[]` only in `toMetricsSettingsRequest`. The last field is the **epic estimate gap tolerance** (`epicEstimateGapTolerancePercent`, a whole percent 0..100, default 25): how far an epic's own estimate may differ from its tasks' total before Data quality's gap card and Home's epic gap chart flag it; it is read at query time, and changing it bumps the config revision like every setting. After it come the **history horizon** controls (A48): a `Switch` "Limit history" (on = `historyHorizonEnabled`) and a `NumberInput` "History horizon (months)" (`min 6`, `max 120`, `allowDecimal={false}`, disabled while the switch is off; the input clamps typed numbers to the range on blur, so the rule `historyHorizonMonths` mainly catches a cleared field). Off sends `historyHorizonMonths: null` and keeps the number in the form (default 24 when the stored value is null); the description under the number prints the SAVED `historyHorizonFrom` the server computed ("Saved: work since {date} counts." / "Saved: no limit."), so the form never does month arithmetic. The bounds mirror `metrics/HistoryHorizon.kt`; the data-source editor's backfill floor reads `historyHorizonFrom` ("Data sources" above); the report pages follow the setting ("Reports", the history horizon bullet). The zone
  picker is a searchable `Select` over `Intl.supportedValuesOf("timeZone")`
  (`utils/metricsForm.ts`'s `supportedTimeZones`, cached). The success toast reads "Saved — reports
  re-derive shortly" — accurate once M3's DERIVE job lands, so the wording needs no follow-up
  change.
- **`components/TeamJiraMembers.tsx`**, mounted on `pages/TeamDetails.tsx` (the page's only member list; the Flow-login
  roster was removed by V30) — D1's dated Jira-user team membership (`GET/POST /api/v1/teams/{id}/jira-memberships`,
  `PUT/DELETE …/{membershipId}`): any authenticated user reads the table (person name resolved
  against its own `displayName` (filled by the list read, departed people included) and, for a row without one,
  `GET /api/v1/jira-users?scope=UNIT` — every account that ever held a membership row is
  UNIT-relevant, so the directory, paged through at pageSize 100 to its `total` (`api/metrics.ts`'s
  `listAllJiraUsers`, capped at 50 pages, `staleTime` 60 s, abortable — React Query's `signal` reaches every
  page fetch, so an unmount ends the walk), names this team's whole history however large the unit
  is; an unresolved account falls back to its raw `accountId`; a failed walk renders its load error inline
  and one the page cap cut short shows a gray `directoryCut` notice; adding a member also invalidates
  `["jira-users","directory"]` so a newly added SITE person is named at once), a "current" badge when `now ∈ [validFrom, validTo)`.
  ADMIN-only mutations: "Seed from Jira Team field" opens `components/JiraSeedModal.tsx` (D1 seeding: a searchable
  Select of the Atlassian Teams from `GET /api/v1/jira-teams` — "name (issue count)" — then, from
  `…/seed-candidates?atlassianTeamId=`, a table of the team's issue ASSIGNEES with a checkbox, issue count and the first/last
  issue day (read in the configured zone, like every membership date); people without a conflict start ticked, a person with an
  overlapping OPEN membership shows an orange "Already in {team}" badge and a disabled, unticked box; a **Starts** column shows the
  day the seed stores (the first issue's day, or the day a team mover left their previous team); the ticked people are stored
  EXPLICITLY per team (everyone without a conflict is ticked on the FIRST load of a team's candidates only, so a refetch never
  auto-ticks someone new); the candidates query has `gcTime: 0` — a decision aid is never served from
  an earlier opening. Apply POSTs `…/seed` with the ticked ids (disabled at 0 or above 100 ticked, with a red hint), toasts
  "Seeded N, skipped M" and the parent re-fetches the roster and the name directory in the background; a failure — the race `409`
  included — renders inline in the dialog. The button waits for the metrics zone like "End membership"), and
  "Add Jira member" opens `components/JiraMemberModal.tsx` (a searchable
  person `Select` over `GET /api/v1/jira-users?scope=SITE` — the whole site directory, since a
  brand-new member may not yet be UNIT-relevant; **server-side search**: the typed term, debounced
  300 ms, is the `q` param (display name OR account id, case/accent-insensitive), the query key
  carries it and `placeholderData: keepPreviousData` keeps the old rows up while the next term loads
  (`isPlaceholderData` still shows the loader); 20 rows per request, and a "Showing the first N of M
  people" hint when the answer is cut, so anyone beyond the first page is found by typing; the
  picked person is held in state and re-added to the options, so its label survives later searches,
  and the label Mantine writes into the box on selection is never sent as `q` — plus a required valid-from and optional valid-to
  date; the directory query shows a labelled `Loader` (`role="status"`) and `aria-busy` while pending and
  an inline red `Alert` (`loadErrorMessage`) on failure — "No matching people" only states a completed
  search for the current term, never during the debounce window or a pending/failed load; the
  exclusion-constraint `409` renders inline in the modal, never a toast), a per-row "End
  membership" (only on the open-ended row — direct PUT setting `validTo` to the start of today in
  the CONFIGURED metrics zone, `startOfTodayEpochMillis(timeZone)` in `utils/isoDate.ts` — a bisection
  for the zone's first millisecond of the day, so DST-change days and skipped midnights are right) and
  Delete (`ConfirmDeleteModal`, the `useDeleteConfirm` precedent). **Membership dates are calendar days
  in the configured zone** (`metrics.settings.time_zone`, the zone the server cuts days in): the table
  renders `validFrom`/`validTo` with `epochMillisToIsoDateInZone`, the add form's days convert through
  `isoDateToEpochMillisInZone` (that day's first instant in the zone — half-open `[validFrom, validTo)`
  semantics unchanged), and "End membership" cuts at the zone's today. The zone is read from
  `GET /reports/filters` (`["reports","filters"]`, any authenticated user — the settings endpoint is
  ADMIN-only and the table is read by everyone); the table reads in UTC until it loads, the End item and
  the form's submit are disabled until then. While it is merely loading that is all; when it FAILED, a
  `ZoneUnavailableAlert` (cause + Retry, `filters.refetch()`) appears above the table and inside the add
  form, and the disabled End item points at it with `aria-describedby`. Adding a member also invalidates
  `["jira-users","directory"]` in the background (End/Delete refresh only the memberships).
  **Rows saved before days moved to the zone hold UTC-midnight instants**: in a zone behind UTC they now
  display a day earlier, and an old UTC-midnight `validTo` can overlap a new zone-midnight `validFrom` by
  the zone's offset (a `409`) — correct it by editing the old row. Changing the settings time zone later
  re-renders every stored instant in the new zone (the instants themselves do not move).
  **Dates are plain `YYYY-MM-DD` `TextInput`s** (`utils/isoDate.ts`'s
  `isValidIsoDate`/`isoDateToEpochMillisInZone`/`epochMillisToIsoDateInZone`; never `toLocaleString()`) — the
  `dataSourceState.ts` `formatEpochMillis` convention — no `@mantine/dates` dependency in this
  commit; it arrives with the Reports period picker, §2.4 of the phase-3 plan).
- **`pages/DataSourceMetricsConfig.tsx`** (`/data-sources/:id/metrics-config`, under the same
  `RequireAdmin` group, reached from a "Metrics configuration" toolbar link on
  `DataSourceDetails.tsx` beside Profile/Inspect) edits the ONE composite
  `DataSourceMetricsConfig` resource (`.claude/docs/metrics.md` "Per-connection metrics
  configuration") over `Tabs`: Statuses (stage `Select` — `METRICS_STAGES`: Not started, In progress, **Waiting** (A30: started, nothing actively worked on, a queue), **Blocked** (A36: started, held by an impediment; replaces the former Blocked `Checkbox` column, gone with `blockedStatuses`) — neither is ever a category default, so choosing it is a user choice and keeps the status listed; the per-domain `DomainStageOverrides` select offers both — Done; a one-line `metrics.config.statuses.stageHint` above the table tells a queue (review, test, deploy) from an impediment (blocked, on hold, waiting for a customer) and notes that Jira's Flagged marker still marks an item as blocked whatever its status — the
  Jira category as a `Badge`, plus a gray outline "Epic" and/or "Task" `Badge` for the workflow(s) that use it — options
  `inEpicWorkflow`/`inTaskWorkflow` (a status of both workflows shows both; none while the split is unknown, and then
  the one-line explanation above the table is hidden too); **by default only the relevant statuses are listed** — `inWorkflow || seenInHistory`
  from the options endpoint — PLUS every status carrying an admin choice (a stage other than its category's default,
  Blocked, any per-domain override) or edited this session (`utils/metricsConfigForm.ts`'s `visibleStatuses`; the
  edited-ids set keeps a row from vanishing under the cursor when an edit restores its default), so a hidden status
  never hides a choice. A "Show all statuses (N)" `Switch` (plain page state, remembered nowhere, shown only when
  something is hidden) lists the rest; with no workflow/history information (no profile yet) everything is listed.
  The filter is display-only: the form state and the full-replace PUT always carry every status, hidden ones at
  their current/default stage. The filtered list drives both the status table and the overrides table below it
  (`DomainStageOverrides`'s `listedStatuses`; its orphan-override check still uses the full `statuses`); under that table `components/DomainStageOverrides.tsx` — a "Per-domain
  overrides" section: a domain `Select` over the Domains tab's CURRENT domain keys (each option counts its
  overrides), then a table with one row per status — the every-domain stage, a stage `Select` whose empty value reads
  "Same as all domains" (= no row; clearing it or the row's remove button deletes the override) and a "Differs"
  badge when the override's stage is not the every-domain one; a domain key renamed on the Domains tab leaves its
  overrides orphaned, flagged by a red inline `Alert` per orphan, and so is an override on a status the connection no longer reports (an `Alert`
  with a Remove button — such an override has no row in the status table and would `400` every save), and the server's `400` shows in the page alert
  — they save with the ONE Save, as `domainStatusStages`), Fields (five `Select`s over the profile-detected custom fields plus
  Jira's own `duedate` system field, labelled with the detected role; **by default only the fields of the projects' field
  scheme are listed**, **per slot**: the three epic slots (estimate, start, due) list options `inEpicScheme === true`, the two
  task slots (task estimate) `inTaskScheme === true`, and the work-category slot the union (`inScheme === true` — D8 reads "the
  task's own value, else its epic's"), via `visibleFields(fields, selected, showAll, scope)` with `scope` `epic`/`task`/`any` —
  PLUS every field currently selected in any of the five slots (`utils/metricsConfigForm.ts`'s `visibleFields`); a scope never
  comes up empty: when its split is unknown or flags nothing it falls back to `inScheme === true`, then (the optional
  `projects/fields` step produced nothing — a dimmed one-line note says the scheme is unavailable) to fields with
  `nonNullCount > 0`, and with no information at all everything is listed. A "Show all fields (N)" `Switch`
  (state inside `FieldsTab`, remembered nowhere, shown only when the default hides something) lists the rest. The filter is
  display-only: the saved `fields` always carry the selected ids), Domains (project key →
  domain key/name), Boards → team (an active-teams `Select`; a `409` marks the changed board
  row(s) inline, the "which row" rule computed by diffing the just-submitted board→team snapshot
  against the last-loaded/last-saved one — `utils/metricsConfigForm.ts`'s `changedBoardIds`),
  Activity types (issue type → activity type) and Work categories (value → category, fetched with
  `?workCategoryField=` the moment a work-category field is chosen on the Fields tab — shown only
  once one is). That is six tabs: the per-sprint Capacities tab is gone (capacity is the team's default
  figure and per-sprint calendars now, domain-model A32; the metrics-config API carries no `sprintCapacities`
  and the options no `sprints`). GET returns computed defaults when `configured: false` — a banner says so ("Showing
  computed defaults — save to confirm them") until the first save. ONE Save does a full-replace
  PUT of every tab's current state (even an untouched tab still submits its own default), a
  success toast reads the same "reports re-derive shortly" wording as `MetricsSettings.tsx`, and a
  `400`/`409` renders the SERVER'S OWN `detail` message inline (not a fixed-vocabulary mapping —
  these messages are admin-facing by design, `.claude/docs/metrics.md`'s validation/conflict
  rules). **`components/MappingTable.tsx`** is the one generic id → editable-cell table every
  mapping-shaped tab reuses (a `Select`/`TextInput` per field column, the row's own
  identity in a fixed left column) — callers own all state, MappingTable is a pure renderer.
  **`utils/metricsConfigForm.ts`** holds the pure state-shaping: `buildInitialState` merges the GET
  response with the options endpoint's reference lists into one row per reference item (mapped or
  not), `buildRequest` is its inverse, `mergeWorkCategoryValues` combines the field-scoped values
  query with whatever category is already chosen, and `changedBoardIds` is the 409 row-marking
  rule; its override helpers (`setDomainStage`, `currentDomainKeys`, `orphanOverrideDomains`) keep the list free of
  duplicate (domain, status) rows.

## Capacity (`pages/Capacity.tsx`, `components/SprintCalendarGrid.tsx`)

Administration > Capacity (A32): how many man-days a team has in a sprint. **Every signed-in user reads it** (the leaf is not
`adminOnly`, the route is outside `RequireAdmin`); editing is the team's manager or an ADMIN, and the server says which per team and
document (`canEdit` on `GET /teams/{id}/capacity` and on the sprint calendar) — the page only disables the inputs, the server is the rule.
**The URL is the selection** (`utils/capacityLinks.ts`: `/capacity?teamId=…&sprintId=…`, read with `parseCapacitySelection`, written with
`navigate(…, { replace: true })`): a team `Select` over the first 100 teams and a sprint `Select` over the team's first 100 sprints,
newest first (`sort=-startAt`; label `name · from → to` with the dates in the configured zone from `/reports/filters` and the last day
read at `end - 1`, the grid's rule; a gray "calendar" badge on a sprint that has a stored calendar; a "first N of M" hint under either
picker when its list is cut). Nothing is auto-picked: the empty state is the picker state; picking another team drops the sprint. **The last pick is remembered**
(`capacity.filter`, `hooks/useCapacityMemory.ts`: the `teamId[&sprintId]` query, written by the pickers only): a bare `/capacity` restores the team once the
team list is in — a team it no longer holds is dropped (the page stays on the empty picker) — and the sprint WITH it, by `replace`, whether or not the picker's
first-100 list holds it (an older sprint is restored too: the calendar read answers for any sprint of the team). Only a `404`/`400` for a restored sprint's calendar
drops it: the memory and the URL fall back to the team and no error shows (the restore is flagged in `location.state`, `capacityRestoredSprint`); the same refusal
for a sprint from a link is shown. A URL naming a team or sprint is taken as written.

Two surfaces for the picked team. **`components/TeamCapacityDefaultCard.tsx`**: the team's ONE default MD figure (`TEAM_DEFAULT`),
with the precedence sentence. Blank is an explicit clear and saves `null`; text that is not a finite number, or a figure outside
0..999999.99, is an inline error with Save disabled — never sent as `null` (`parseFigure`); no negative sign, "," is read as the
decimal separator. It is keyed by team only: a reload that brings a new stored figure is adopted while the field is untouched and never
replaces what is being typed. **`components/SprintCalendarEditor.tsx`** (keyed by team and sprint, NEVER by `updatedAt`): the team
coefficient, the grid, two clearly separate figures — "Capacity in reports" (the effective `derivedCapacityMd` + source as of the last
DERIVE) and "This calendar" (the live formula over the grid as shown, deliberately not an `aria-live` region; the "Saving it makes
reports use it" hint appears while the calendar is unstored or edited) — and Save (a full-replace PUT of the document without the
grid-only person fields, then `refreshQueriesAfterMutation(queryClient, ["capacity"])` and the fixed-vocabulary toast) / Reset.
Save and the team coefficient are disabled when the roster is empty (a save would store a zero calendar that overrides the team default).

**The draft is re-seeded explicitly, never by remounting.** The editor keeps a `base` (the document the draft started from) beside the
`draft`. After a successful save the sent document becomes the `base` (edits typed during the request stay unsaved). When a NEW loaded
document arrives (a focus refetch — the query hands back the same object while nothing changed): a clean draft adopts it; a dirty draft is
kept and an orange "This calendar changed elsewhere — Reset to load the latest" alert shows when the editable content differs; Reset
adopts the latest. Dirtiness is a cheap structural compare that short-cuts on identity (`sameDraft`: an edit replaces only the person it
touches), not a serialisation. **Unsaved edits are guarded**: both surfaces report dirtiness to the page (`onDirtyChange`), whose
pickers ask through `ConfirmActionModal` ("Discard changes?", `common.discard.*`) before dropping a dirty draft, and
`hooks/useBeforeUnloadGuard.ts` (ported from Lettuce) triggers the browser's own prompt on reload/close. In-app navigation through the
sidebar or the back button is NOT blocked: Lettuce's `useDiscardGuard` does that with the data router's `useBlocker`, and Flow runs on a
plain `BrowserRouter`, so it stays unported. A `403`/`404`/`409`/`400` renders a fixed message (`capacity.error.*`), never the server's
text; a `400`/`404`/`409` also refetches so Reset loads the current sprint. After a save the re-derive is asynchronous, so the editor
re-reads the calendar up to four times (1.5, 3, 6, 12 s, cleared on unmount) until "Capacity in reports" moves, then stops.
`window: null` (no start date, or over 60 days) renders a note and no grid.

**The grid** is ported from Lettuce's `DaysOffMonthGrid` (the real `<table>` with scoped headers, the sticky name column, the legend; the
today marker, share/team lines and month logic were dropped; the public-holiday lock below is Flow's own). Rows = people, columns = the sprint's `days`; a `Checkbox`
turns a whole day or person off (shading follows `enabled === false` from the data, never the weekday), a person's coefficient and
each cell are `CapacityNumberInput`s (0..2, 0..1, two decimals, no minus sign, "," or "." as the separator — a field that keeps its own
text while typing and commits the parsed, clamped number, so "0." survives). The footer is the per-day sum of the counted cells. **The server withholds per-person coefficients
from a caller who cannot edit** (`coefficient: null`): the column is not rendered at all and the total is the server's `capacityMd`.
`utils/capacity.ts` mirrors the server kernel (`CapacityKernels.kt` `calendarCapacityMd`) in integer hundredths with `roundScaled`, and
is tested against the same hand-built document as `CapacityKernelsTest`; the grid is a pure renderer (callers own the draft). **Every
edit is a function of the previous draft** (`onChange(prev => withCell(prev, …))`, the grid never closes over `draft`), so two commits in
one tick both land; `DayHeader`, `PersonRow` and `AvailabilityCell` are `memo`ised on their own slice (cells indexed per person by day in a
`Map`, the footer totals one pass `dayCapacityTotals`, one `Intl.DateTimeFormat` per language), so a keystroke re-renders one cell. The
weekday initials are `aria-hidden` (the day checkbox names the day). Legacy
sprints and pre-V24 `CONFIGURED` figures read as a source label only.

**Public holidays are locked off** (the `/api/v1/public-holidays` dictionary, below). The server answers a holiday day `{day, enabled: false,
holiday: "<name>"}` whatever was stored and projects a PUT's `enabled: true` on a holiday back off, so the grid never edits one: the day's
`DayHeader` shows an unticked, disabled checkbox whose label is "<day> is a public holiday: <name>" (`capacity.grid.holidayDayAria`), a
`title` tooltip "Public holiday: <name>" and the hatched shade (`classes.holidayDay`: the off shade plus the Deep dive's diagonal non-working
hatch, gray.6), its cells are disabled, and the legend gains a "Public holiday (always not counted)" entry only when the sprint has one.
`utils/capacity.ts` mirrors the lock: `isCounted(day)` (on AND not a holiday) feeds `calendarCapacityMd` and the footer, `withDayEnabled` leaves
a holiday day alone, `toDraft` states a holiday day `enabled: false` (so a document that somehow said otherwise is still clean against the base
built the same way), and `sameDraft` also compares `holiday` (a dictionary change while a clean draft is open is adopted; under a dirty one it
raises the "changed elsewhere" alert). The PUT body keeps `{day, enabled}`; a holiday goes out as `enabled: false`.

**A day an earlier sprint holds is locked off the same way (A42).** The server answers each day `claimedBy: {sprintId, sprintName} | null`
beside `holiday`: the earlier sprint of the same team whose window already holds the day. Such a day arrives `enabled: false` and a PUT's
`enabled: true` is projected off, so the grid never edits it: the `DayHeader` checkbox is unticked and disabled with the label "<day> is counted
in <sprint>" (`capacity.grid.claimedDayAria`), the `title` is "Counted in <sprint>" (`claimedTooltip`), the column wears the off shade plus
`classes.claimedDay` (the OTHER diagonal of the holiday's hatch, wider-spaced and heavier, gray.6), its cells are disabled, and the legend gains
"Counted in an earlier sprint (not counted here)" (`legendClaimed`) only when a day is claimed. A day that is both a holiday and claimed reads
as the holiday (`lockOf` in the grid). `utils/capacity.ts` mirrors the lock through one private `isLocked` (holiday OR claimed): `isCounted`
(on AND not locked) feeds `calendarCapacityMd` and the footer, `withDayEnabled` leaves a claimed day alone, `toDraft` states it `enabled: false`,
and `sameDraft` compares `claimedBy` (id and name), so a claimed day is never an unsaved edit while a changed claim under a dirty draft raises
the "changed elsewhere" alert. The PUT body keeps `{day, enabled}`; a claimed day goes out as `enabled: false`. The unstored grid's total is
the DEFAULT capacity ("Capacity in reports") once the day is skipped.

## Team work-category targets (`pages/TeamWorkCategoryTargets.tsx`)

`/teams/:id/work-category-targets` (`utils/teamLinks.ts`'s `teamWorkCategoryTargetsPath`; outside `RequireAdmin`; domain-model A43, the API wrappers in `api/teams.ts`): the share of a team's
effort each work category should take, in whole percent. It is reached from the **Teams page row action** "Work-category targets of <team>" - for an ADMIN an item of the row
kebab, for everyone else a single icon link in the same (now always present) last column. **Every signed-in user reads it**; only `useAdmin()` renders the inputs, Save and Clear
(the server is the rule - a `403`/`404`/`400` shows a fixed inline message, `teams.targets.error.*`). The header reads "Work-category targets: <team>" (the team from
`["teams","detail",id]`, the figures from `["teams","work-category-targets",id]`); an unknown id, a non-numeric id or a `404` is the shared not-found state (`EditPageLoadState`, `teams.notFound`).
The table is the server's `categories[]` in the server's order: `UNCATEGORIZED` is labelled "No category"; a row with `mapped: false` wears an orange "Not mapped any more" badge,
takes no input and is NOT counted in the admin's total (a save drops it - the page says so; a regular user sees the stored set as it is, unmapped rows included, and its stored total).
Inputs are whole numbers 0..100 (`allowDecimal={false}`, `clampBehavior="strict"`), an empty field = no target for that category. **The save rule is mirrored, not invented**: the filled
fields must total exactly 100 or all be empty (Save then sends `{targets: []}`, a clear); anything else shows a red inline message with the running total and disables Save. "Clear targets" (enabled
only while something is stored) asks through `ConfirmActionModal` first and PUTs `[]`. The editor is keyed by the stored set's `updatedAt`, so a save brings the fresh server state in while a
refetch that changed nothing never replaces what is being typed; both writes refresh only the page's own query key, with the fixed-vocabulary toasts `teams.targets.toast.*`. The Costs report reads these targets ("Work-category widgets" under Costs) and links here from its empty state.

## Public holidays (`pages/PublicHolidays.tsx`)

Administration > Public holidays (`/public-holidays`, `IconCalendarOff`, next to Capacity): the global dictionary of days nobody works
(Lettuce's page, ported; Lettuce's `DAYS_OFF` feature gate is NOT ported). **Every signed-in user reads it** (the leaf is not `adminOnly`, the
route is outside `RequireAdmin`); the add strip and the delete buttons render for `useAdmin()` only and the server is the rule (`403` shows
a fixed message). A holiday is always a non-working day: locked off in capacity calendars (above), and left out of the default capacity and
of every report's working days, so an add or delete re-derives the metrics (the toasts say "reports re-derive shortly"; both mutations also
refresh `["capacity"]`). The endpoint is unpaged and spans many years (the migration seeds the Polish statutory holidays 2017-2030), so the
page filters CLIENT-side to ONE year at a time: a `Year` `Select` defaulting to the current year in the BROWSER's local time (`localYear`, not the UTC `todayIsoDate`) whose options are the years in the data plus
the current and the viewed one (`utils/publicHolidays.ts`'s `holidayYears`/`holidaysOfYear`), and adding a holiday moves the picker to the
new row's year. The table is a `ColumnTable` (date, weekday in the UI language via `weekdayName`, name, an admin-only delete icon button
"Delete the holiday <name>" behind `ConfirmDeleteModal`). The add strip is a `<form>` of plain `TextInput`s (Enter submits; date `YYYY-MM-DD`, validated with
`isValidIsoDate`; name 1..100 characters), Add disabled until both are valid; a failed delete answers "Delete failed (<status>)" (its own `error.deleteFailed*` keys, never the save wording); a `409` is "A holiday already exists on this date." and a `400`
the fixed invalid message, inline, never a toast. The route is `utils/publicHolidayLinks.ts`'s `publicHolidaysPath`; the API wrappers live
in `api/metrics.ts`; the copy in `locales/*/publicHolidays.json`.

## Reports (`pages/ReportCommitments.tsx`, `components/Report*.tsx`, `utils/reportFilter.ts`)

The v0.3.0 report pages (`.claude/docs/reports.md` is the API; every report is any-authenticated,
D12 — routes sit under `RequireAuth`, never `RequireAdmin`). Nineteen pages carry the twenty reports
(report 13, the backlog in sprints, rides the Estimated backlog page): the shell (Reports nav
section, `ReportTabs` — real router links with `role="tab"`, so middle-click opens a report in a new tab), the filter bar, the shared blocks and the three tab groups — Delivery
(Commitments, Throughput, Sprint consistency, Cycle time; `DELIVERY_TABS`), Estimation (Task accuracy,
Epic accuracy, Adjustments, Reported time, Task estimates; `ESTIMATION_TABS`) and Flow metrics (WIP, Estimated
backlog, Aging WIP, Blocked time, Epic progress; `FLOW_TABS`) — plus the Costs and Data quality
pages. A new report appends a page, a tab (the tab lists live in
`utils/reportLinks.ts` — each group's nav leaf lists every tab route in `NavLeaf.activeFor`, so it
stays highlighted on all of them) and a `reports.<name>` key block. Every page composes the same
skeleton — `hooks/useReportPage` (filters query → keyed page query), `components/ReportPageShell` (the tabs, `ReportFiltersStatus`, the
no-teams empty state and the body once the filters are in — its render prop is handed the loaded filters; `empty` replaces the default "no team" rule for a report
that also reads by domain or epic), `hooks/useHistogramDrill` (the `openBar(view)` of a histogram's `onDrill`; `segment: "none"|"required"` and a per-opener card for the
variants), `ReportFilterBar`, `ReportMetaNote`, `ReportChartCard`, `ReportSprintsTable` (sprint · team ·
completed · the report's figures · the orange drift badge with the frozen figures as text),
`ReportGroupsTable` — copy `pages/ReportCommitments.tsx`.

- **The history horizon (A48) in the period controls and the meta note.** `GET /reports/filters` echoes `historyHorizon` (`{months, from}` or `null`);
  `ReportFilterBar` hands `filters.historyHorizon?.from` to `ReportPeriodControls` as `horizonFrom`: the custom range's `DatePickerInput` gets
  `minDate={horizonFrom}` (beside the existing `maxDate` of today), and `presetRange(preset, today, horizonFrom)` raises a preset's `from` to that day (only
  Last 365 days can be longer than a six-month horizon), so the preset the user picked still reads as itself: `activePeriodChoice`, `presetOf`,
  `readRememberedFilter`, `activeReportFilterCount` and the print header's `periodText` take the same optional `horizonFrom`, and `usePeriodChoice` /
  `useReportFilter` (the `defaultPeriod` seed, the remembered-preset restore) pass it in. Every report page that mounts the bar gets this, Compare people and
  the drills' pages included; the Deep dive has no date inputs of its own (its selection is sprints or epics), so there is nothing to bound there. The server stays
  the authority (a longer period is clamped and `meta.from` says so, a period wholly before the horizon is `400`); the picker and the presets only keep the UI from
  offering it. `ReportMetaNote` prints a second dimmed line from `meta.historyHorizon` while one is set: "Reports show work since {from} (history horizon: {months}
  months)" (`reports.meta.historyHorizon`, plural by `count: months`, EN and PL).

- **Print / save as PDF and Back to top** (every report page; both are shared, a new report gets them by following the skeleton):
  every report page opens with `components/ReportPageHeader.tsx` (never a bare `PageHeader`) — `PageHeader` plus the
  `PrintReportButton` action (`window.print()`: vector text and SVG charts, no dependency, CSP-safe) plus the print-only
  `ReportPrintSummary` (report name, the filter in words from `utils/reportPrintSummary.ts` `reportFilterLines(filter, filters,
  controls)` prints a toggle at its effective value and WIP's domain-for-team as such; the Deep dive passes its own `lines`,
  a page's own state goes in `extraLines` (Throughput's view) and `tail` adds the long-tail share — and the generation time in the
  configured zone, stamped on `beforeprint`). The whole app prints in the LIGHT scheme: `hooks/usePrintLifecycle.ts` (mounted by the
  shell; `beforeprint`/`afterprint` plus the `print` media query's change for Safari) feeds the `utils/printing.ts` store that
  `PrintAwareMantineProvider` (main.tsx) turns into `forceColorScheme="light"` — through React, so `useComputedColorScheme`
  consumers (heat cells, chart marks) switch too; never persisted. The paper layout is `src/print.css` (`@media print`, imported
  in `main.tsx`): it hides the navbar/header, drawers/modals, tooltips, the Affix and anything marked `data-print="hide"` (the
  filter bar, report tabs, pickers, the print button, the long-tail control, the Throughput view switch, the drill hint lines, a
  disclosure's toggle), shows `data-print="only"` blocks, opens every `ScrollRegion` (no `overflow`, no `max-height`, no min-width),
  keeps charts and table rows whole and asks for colours to print. Cards are deliberately NOT kept whole (a card taller than a page
  would leave the page before it blank), and recharts needs no print rule (checked with `page.pdf()` on A4: it re-measures; a
  forced `max-width` blanks it). A table behind a closed `DailyTableDisclosure` is not in the page, so it does not print.
  `print.test.tsx` pins every Mantine class the sheet names against what Mantine renders. A control that is no content gets
  `data-print="hide"`.
  `components/ScrollToTop.tsx` (Mantine `Affix` + `Transition`, bottom-right, shown past 400 px of scroll) is rendered once by the
  app shell for `/` and `/reports/*`: smooth scroll — instant under `prefers-reduced-motion` — then focus on the page's
  `#main-content h2`; hidden on paper.

- **The URL is the filter** (`utils/reportFilter.ts`, `hooks/useReportFilter.ts`): `from`/`to`,
  `lastSprints`, `sprintId`, `teamId`, `accountId`, `domainView`, `domain`, `activityType`,
  `workCategory`, `bucket`, `connectionId`, `tailPercent` — deep-linkable, parsed forgivingly (an invalid or
  conflicting param is DROPPED, never sent; the period is exclusive with precedence `sprintId` >
  `lastSprints` > dates), serialized in one canonical key order (that string is also the page query
  key). Params this module does not own survive `applyReportFilter`; switching report tabs
  (`reportHref`) drops the report-specific params (`domainView`, `domain`, `activityType`,
  `workCategory`, `bucket`, `tailPercent`) the target report has no control for — and the page-local `view` (throughput's chart switch, `PAGE_PARAMS`: no filter, never sent or keyed) anywhere but throughput, and the page-local `drill` (the open drill drawer — "Drill to items" under Chart rules) on every hop — so a filter the user
  cannot see or clear never follows them (`REPORT_SPECIFIC_PARAMS` in `utils/reportLinks.ts`; `epicId` is one of them, kept only by epic progress; a page's normalizer drops the rest through `dropReportSpecific(filter, reportSpecificKeep(path))`, so the table stays the one source). A team id the reference data no longer lists prints `#<id>` through `teamLabel` (`utils/reportFormat.ts`). Presets are stored as
  absolute `from`/`to` dates (calendar days in the configured zone) and recognised again by
  `activePeriodChoice`. **Remembered filters** (client-side, per browser, never sent anywhere; `hooks/useReportFilter.ts`, pure half
  `utils/rememberedFilter.ts`, storage `readRememberedQuery`/`writeRememberedQuery` in `hooks/useStoredState.ts`). Each screen remembers the
  filter the user LAST SET on it under `flow.viewSettings.<screen>.filter` (screen = `reports.<report>`, the key the filter bar's open flag
  already uses; Deep dive `reports.deepDive`, Capacity `capacity`): a plain string, the canonical URL query that page writes — the managed
  filter params it has a control for plus its page-local switches (throughput's `view=cumulative`, aging WIP's `kind=epics`), never `drill`
  and never an epic progress `epicId`. `""` means "the user cleared everything"; absent/corrupt/not a string means "nothing remembered". *Precedence:*
  (1) **a URL that carries any filter param is taken as written** (deep links win, a copied unit-level link stays unit-level) and is NOT stored;
  (2) a **bare visit** (no managed param at all — a nav click, the palette, a typed address; page-local params like `view` do not count) restores
  the screen's memory once the reference data is in: every value is checked against it (`dropStaleReferences` — a deleted team and its people, a
  person who left the team, a sprint the team no longer lists, an unknown domain / activity type / work category / connection is dropped, a
  period that falls with its sprint reverts to the default), then goes through the page's own `normalize` and `requiredTeamId`, and the URL is
  rewritten (`replace`) so a copied link means what the page shows; (3) with NO memory of its own, the screen falls back to the shared remembered
  team (below). *What writes:* only the user's own change — `setFilter` (the filter bar and its siblings), a page-local switch
  (`useThroughputView`/`useAgingKind` call `rememberPageParam`, which writes ONLY that param over the existing memory — the filter in the URL may be a link's
  and a link is never stored; a memory holding only a switch still lets the shared team stand in), and an in-page link that CHANGES the settled filter (a row name,
  a breadcrumb: a PUSH onto the same page whose `reportQuery(filter)` differs from the previous one — opening a drill or any other non-filter push stores nothing). A restore, a normalising `replace`, Back/forward and arriving by a tab hop or a Home link never
  write. A filter change writes BEFORE it navigates, so the bare address a cleared filter can leave never meets the previous memory. Every URL writer builds on the latest params, not its render's (`hooks/useLiveSearchParams.ts`), and `setFilter` applies only the keys its control changed (`rebaseReportFilter`; limits in its JSDoc). The values
  are read at render time (the writers and the next bare visit sit in different hooks). **A relative preset period is remembered
  symbolically**: a period whose dates match a preset as of today in the configured zone (`presetOf` → `activePeriodChoice`: Last 30/90/365 days, This/Last
  month, This quarter) is stored with a reserved `preset=<id>` key beside its dates (never in the URL or a request), and a restore re-resolves it with
  `presetRange` against today, so a restore days later is still "Last 30 days" rather than a stale "Custom"; a custom range stays absolute, `lastSprints`/`sprintId`
  are symbolic already, and an unknown preset id is ignored. **The shared remembered team** (`useStoredState`, `reports.teamId`, the original memory, kept
  and still the only thing a report with no memory of its own has): a bare report starts on the last team the user picked through ANY report's
  Team control and the URL is rewritten to say so; only the Team control touches it (picking stores, clearing clears). A page that is never read for
  the whole unit (`teamRequired`) cannot be left on "All teams", so a stored filter without a team never brings the unit-wide view back: the restore runs through
  `requiredTeamId` like any link. **The memory ends with the session**: `clearSession` (logout and a definitive sign-out alike) calls `clearRememberedFilters` (`hooks/useStoredState.ts`), which removes every
  `flow.viewSettings.*.filter` key, `reports.teamId`, the people pickers' remembered teams (Compare people's `reports.comparePeople.teamA|teamB`, Person trend's `reports.personTrend.team`) and Home's `home.teams` pick (best-effort) — teams, people and periods belong to the person who left; the list pages' `<view>.filter.<name>`
  text filters and the other view settings stay. The Raw issue inspector remembers
  nothing: its one input is a lookup key for one connection, not a filter, and restoring it would fire a request nobody asked for.
- **`components/ReportFilterBar.tsx`** is a controlled component (`filters`, `filter`, `onChange`);
  optional controls (domain view "delivered in / earned in", domain, activity type, work category,
  and the week/month `bucket` `SegmentedControl`) render ONLY where the report passes them
  in `controls` — sprint consistency passes `teamRequired`, `noMember`, activity type and work category (it is read per team, always for every member — `dropMember` drops a pasted `accountId`, and `NO_MEMBER_PATHS` in `reportLinks.ts` drops it from tab links; its `ReportGroupsTable` takes `drill={false}`, so the per-assignee names are plain text; with a slice set the page notes the figures are live, without the frozen snapshot or drift badge), commitments passes only the two scope flags below, throughput passes those two plus domain view, domain,
  activity type, work category and bucket. Every report is also a
  command-palette entry (`REPORT_PALETTE_LEAVES`, palette-only: the sidebar carries one Delivery leaf).
  **The bar is collapsible** (`FilterPanel`, the list pages' component — not re-ported): the controls sit in its body, which is unmounted
  while folded, and it starts FOLDED. The open flag is remembered per report under `flow.viewSettings.reports.<report>.filtersOpen`
  (`useReportPage` returns the `storageKey` `reports.<report>`; the page hands it to the bar as a required prop — the Deep dive keeps its
  own selection panel, and Home has no filter bar). The toggle's badge is `activeReportFilterCount` (`utils/reportFilterCount.ts`): how many
  dimensions the bar OFFERS and that differ from the report's default — a period other than the default last-90-days preset, a team, a
  member, and where the report has the control a domain, connection (more than one), activity type, work category, a domain view other than
  the report's own, a bucket other than WEEK, a WIP keying other than STAGE (a column keying nothing can honour reads as STAGE) or an item
  kind other than TASK — so a narrowing nobody sees (the remembered team, a pasted link) still shows on the folded toggle. Tests unfold it
  with `openReportFilters()` (`test/reportFilters.ts`), e2e with `openFilters(page)`.
  The bar is only the frame: `ReportPeriodControls` (with the `hooks/usePeriodChoice` hook), `ReportScopeControls` (team, member, domain, connection and the sprint-survival rule) and `ReportSliceControls` (+ `ReportSliceSelects`: activity type, work category and the segmented toggles) own the controls, `ReportFilterSelect` is their shared clearable select, and `ReportControls` lives in `utils/reportFilter.ts`. Its scope flags are the team-per-report pages' (`teamRequired`, `multiMember`; see "Commitments" and "Throughput"), WIP's (`teamOrDomain`, see "Flow metrics group", shared by Estimated backlog and Epic progress) and Epic progress's own (`epicScope`). The generic `components/ColumnTable.tsx` (`ColumnDef`: header, render, align; `GroupColumn`/`SprintColumn`/`QualityColumn` are aliases of it) is the table for any single-header-row, plain-cell table (optional right alignment, any `Table` props such as the accessible name, spacing or `stickyHeader`; an outer `ScrollRegion` wraps it as before): 24 files use it (the report tables, the data-source profile/inspector/details/cursor/test-result tables, the metrics-config `MappingTable` and `DomainStageOverrides`), and a new table of that shape should too. 10 files still hand-roll a Mantine `Table` (`Table.Thead`) because the shape does not fit and the API is deliberately not widened to absorb it: the ARIA-grid Deep dive matrix (roving tab stops, per-cell props), the Deep dive summary table (row-header `Th`, footer totals row, caption, sticky first column, `Td` `colSpan`), the Deep dive bucket table (raw `tr` rows with a `th scope="row"`), the sortable list pages (`Users`, `FeatureFlags`, via `SortHeader` plus loading/empty/pagination rows in the body, the registries' `RegistryListTable` likewise), and the member roster tables (`TeamJiraMembers`, `JiraSeedModal`: a header cell carrying its own `aria-label`/width and a per-row actions cell).
- **Commitments** (`pages/ReportCommitments.tsx`, `components/CommitmentsChart.tsx` + `CommitmentsUserTables.tsx`, rows and columns in `utils/commitmentsReport.ts`; route `/reports/commitments` — the report was called Velocity until "velocity" came to mean what a team DELIVERS per sprint, so `/reports/velocity` stays a `components/LegacyVelocityRedirect.tsx` `<Navigate replace>` that keeps the query string and hash;
  `.claude/docs/reports.md` "Report 1"). With Throughput (below), Cycle time, Reported time, Adjustments and Task accuracy (all below, at Distributions) the only reports that pass `ReportControls.teamRequired` and `multiMember` (the page hands its one
  `CONTROLS` constant to both `useReportPage` and the bar). **A team is required**: the capacity and the per-person split are team figures, so the Team
  select is not clearable (no "All teams", the picked option cannot be clicked off) and a URL without a team is given one — `useReportFilter`
  applies `requiredTeamId` (the team that lists a linked `sprintId`, else the remembered `reports.teamId` when still listed, else the first team by name) and
  rewrites the URL with `replace` (a `teamId` the loaded list does not know — a deleted team, `0` — counts as absent, once the teams have loaded, and its members are dropped with it); `useReportPage` holds the query back until the filter has a team, so the unit level is never requested. No teams at all
  is one `EmptyState` and no request. The required team is not a narrowing, so it never counts on the folded toggle's badge (`activeReportFilterCount`).
  **Several members**: the member control is a `MultiSelect` (`ReportFilterMultiSelect`, max `MAX_ACCOUNTS` = 8, searchable, clearable, shown once a team
  is set; once 8 are picked its description says "Up to 8 people", and its search input may shrink to 48 px so one pill and the input share a line) over the same options as the single select, and `ReportFilterState.accountIds` is the REPEATED `accountId` URL key (`?accountId=a&accountId=b`), distinct,
  capped at 8, order preserved. Only a multi-member page parses it that way (`parseReportFilter(params, { multiAccount: true })`, passed by `useReportFilter`
  from the same flags): every other page keeps reading `accountId` as its first value and the server answers a repeat with `400` there — so `reportHref` (the tab links) keeps only the FIRST `accountId` when leaving a `MULTI_ACCOUNT_PATHS` report (commitments, throughput, cycle time, reported time, adjustments, task accuracy, task estimates; moving between those seven keeps every pick), and drops them entirely when arriving at sprint consistency, epic accuracy or epic progress (`NO_MEMBER_PATHS`). The report is
  requested with that array as repeated keys (`reportQuery`), and a team change drops the people. **Two modes, decided by the SHOWN report's
  `meta.level`** (so a refetch over kept data never mixes shapes). *Team mode* (no people picked: TEAM level): `CommitmentsChart` is a Mantine `CompositeChart` on ONE
  MD axis — the committed (`flow.6`) and final (`flow.8`/`flow.4`) bars as ever plus the sprint's capacity as a dashed neutral line (`CHART_COLORS.capacity`,
  `gray.6`) with 8 px dots; a sprint without a capacity is a gap (`connectNulls` off), and with none at all the series is left out. The capacity's
  source (`CALENDAR` / `TEAM_DEFAULT` / `DEFAULT`, plus the legacy `CONFIGURED` of a not-yet-re-derived row) is told in words, never by colour: the
  tooltip names it, and the table cell carries a gray "team default" or "default" (members × working days) `Badge` — none for a calendar (or
  a legacy configured figure). The sprints table adds **Capacity (MD)** and **Load** (initial MD ÷ capacity as a percentage — over 100% is shown as is — a dash when the
  capacity is null or 0; `loadFraction`), with a one-line note under the table. *User mode* (one or more people, `level === "USER"`): no capacity (it is a
  team figure, null at this level); `CommitmentsChart` is a `BarChart` with two stacked columns per sprint — `stackId` "committed" (left; a translucent
  fill, `DEEP_DIVE_FILL_ALPHA`, inside a solid 2 px edge, the Deep dive's idiom) and "final" (right; solid, ringed in the surface) — each cut into one
  segment per picked person (`commitmentsUserChart`: data keys `c<i>`/`f<i>`, since account ids are not safe data keys). People are arbitrary names, so they wear
  the categorical `CATEGORY_PALETTE` by position (seven hues, neighbours at least ΔE2000 22.9 apart, never a semantic hue) — but it has seven entries, so the
  8th person (the cap allows eight) repeats the first colour. **People are therefore numbered by stack position** (`personLabel`: "2. Bob"): in the page's own legend (a list), the
  tooltip ("2. Bob · initial"), the per-person totals and the per-sprint share table, so identity never rests on colour. The legend's two treatment
  entries are neutral text-colour glyphs (tall columns, outlined = committed, solid = final), never a swatch in a person's hue; committed vs final is told by
  fill, position and the legend. One person is the same shape with one segment per stack. The tables: the sprints table (the picked people's SUM, drift
  and frozen figures as ever, no capacity/load), "Totals per person" (`groups`, plain text — "By member" stays the team level's drill table) and, with
  two or more people, "Each person's share of each sprint" (every chart number, one row per sprint and person, in a `ScrollRegion` capped at 480 px).
  **Drill** ("Drill to items" below): a double-click on the initial or final bar (in people mode on one person's committed or final segment, `c<i>`/`f<i>` carrying that person) or a click on a sprint row's **Initial** (MD or items; "… show the items of <sprint> · Committed") or **Final** (MD or items; "· Final") figure -- link-styled buttons, a zero figure plain text; each person's share row has the same four -- opens `SprintDrillDrawer` -- `GET /reports/sprint-consistency/items?sprintId=…&bucket=COMMITTED|FINAL` with the page's own filter, its period narrowed to the one sprint (`utils/sprintDrill.ts`: `sprintDrillFilter`, `commitmentsHitTarget`, `commitmentsDrillCount`). The capacity line is not a bar and has no drill.
  **By epic** (`components/CommitmentsEpicSection.tsx` over the shared `SprintEpicSection.tsx`; `.claude/docs/reports.md` "Report 1", `byEpic`): a second card under the main chart, "Initial and final scope per sprint, by epic" ("How many epics each sprint's scope spreads over …"), drawn whenever some sprint carries a segment (`hasEpicSegments`; a report whose `byEpic` is empty everywhere has no card, no legend and no table) and in BOTH modes — the server has already narrowed the figures to the picked people's sum, the page only renders them. The chart (`SprintEpicChart.tsx`, a lazy chunk, MD on its one axis; the page has no MD/items switch: the item counts are in the table and behind the drill) is user mode's idiom: two stacks per sprint, **initial** (left, a translucent fill inside a solid 2 px edge) and **final** (right, solid, ringed in the surface), each cut by the same segments in the order the server sends (`orderedEpicSegments`, the union of the sprints' own lists, "Other epics" then "No epic" last; data keys `i<n>`/`f<n>`, `epicDataKey`). **Colour** (`utils/chartColors.ts`, `epicSegmentColour`): the Nth top epic wears the Nth colour of `EPIC_PALETTE` (the categorical palette without its gray — six hues for the server's six), the same in both stacks and every sprint because the series list is the chart's; "Other epics" is the palette's `gray.6`; "No epic" is a second neutral picked per scheme, `NO_EPIC_COLOR` (`gray.8` light, `gray.4` dark: 11.51 / 10.73 on white / canvas and 9.09 / 11.03 on dark paper / canvas, ΔE2000 31.0 / 19.8 from `gray.6`, pinned by `chartColors.test.ts`; no single gray clears 3:1 on both schemes). Identity never rests on colour: the page's own legend (`ChartLegendList`, a swatch and the words of every segment, then the two stacks' neutral glyphs), the tooltip (only the segments the hovered sprint has, "FLO-12 Checkout · initial") and the table name every segment. The two special segments are localised by `key` (`reports.epics.otherEpics` / `noEpic`, "Pozostałe epiki" / "Bez epiku"), never by the server's English `label`; an epic is its `label` ("KEY Summary"), the key a `JiraKeyLink` in the table (`EpicSegmentName`). The table ("Initial and final scope per sprint and epic", in a `ScrollRegion` capped at 480 px) has one row per sprint × segment the sprint has — Sprint · (Team, only when several teams share the chart) · Epic · Initial (MD) · Initial (items) · Final (MD) · Final (items) — and its segments add up to the sprint table's row exactly. A rule note under it says the six biggest epics have a colour each, the rest fold into "Other epics", and work without an epic is "No epic".
  **Drill:** every nonzero figure of the table is a `DrillFigure` ("8 — Initial (MD): show the items of Alpha 2 · FLO-10 Checkout · Committed") and a double-click on a segment opens `SprintDrillDrawer` with the sprint's COMMITTED / FINAL bucket narrowed to the segment (`epicSegmentTarget`, `epicHitTarget` in `utils/sprintEpics.ts`): `GET /reports/sprint-consistency/items` with `epicSegment` = the epic's id, `NO_EPIC` or `OTHER_EPICS`, and for "Other epics" the chart's own top epics as the repeated `topEpicId` (`topEpicIds`: the distinct `epicId`s of the response's segments — the server cannot recompute its ranking for one sprint). The target is `sprint:<teamId>:<sprintId>:<BUCKET>:e=<key>[,<topEpicId>…]` (`reportDrill.ts`; a malformed `e=` part is ignored like any other garbled drill), the drawer's title names the segment ("Committed — FLO-10 Checkout", over the sprint; a deep link before the report is in hand falls back to "Epic 101"), `commitmentsDrillCount` compares the server's bar with the segment's own item count, and there is NO person select on an epic drill (the segments are the picked people's sum, never split both ways). Focus returns to the by-epic card (`data-drill-anchor="commitments-epics"`), via the hook's anchor function. Tests: `ReportCommitmentsEpics.test.tsx`, `utils/epicSegments.test.ts`, `utils/sprintEpics.test.ts`, the `reportDrill`/`sprintDrill` codec and count cases, the colour cases of `chartColors.test.ts`; e2e: the golden-sprint Commitments journey.
- **Throughput** (`pages/ReportThroughput.tsx`, `components/ThroughputChart.tsx` + `ThroughputBucketTable.tsx` + `ThroughputUserTables.tsx`, rows and columns in `utils/throughputReport.ts`;
  `.claude/docs/reports.md` "Report 2"). Throughput is what is delivered over any time unit by any group of people, in MD (1 SP = 1 MD; the chart's one axis) and items (the tables and tooltips). It copies Commitments' scope:
  **a team is required** and **several people** (`CONTROLS` carries `teamRequired` and `multiMember` next to its domain view, domain, activity type, work category and bucket; the normalization, the 8-person cap, the repeated `accountId` and the no-teams `EmptyState` are exactly
  Commitments'; the page shows what the REPORT's `meta.level` says, `USER` = people mode). **Per period or cumulative**: a `SegmentedControl` ("Chart view": Per period · Cumulative) sits between the meta note and the first card — beside the charts, NOT in the filter bar, so it never counts on the toggle's badge — and
  is the URL's `view=cumulative` (`hooks/useThroughputView`; the per-period default is the absent param). It is no filter: not in `ReportFilterState`, not in the request or the query key, so switching refetches nothing; `reportHref` keeps it only for throughput (`PAGE_PARAMS`). Both views have a chart in both cards: the period card (per week/month) and the sprint card (per sprint, completion order, the table with the drift badge below it).
  `throughputSeries` (pure, tested) turns time-ordered slots into rows holding each slot's own figures, the running totals (`cumulativeMd`/`cumulativeItems`, noise-cleaned) and — people mode — the same per person (`shares`, index-aligned with `people`, first-seen = request order, a person a slot omits counts 0);
  `throughputPlot` picks per-slot or running figures for the chart. *Whole team*: per period a teal (`CHART_COLORS.delivered`) Delivered bar beside a gray (`CHART_COLORS.actualCost`) Actual cost bar, cumulative one teal stacked area (running total) under a dashed gray cost line, with the chart's legend. *People mode*: two stacks per slot (Delivered — each person a segment ringed in the surface; Actual cost — translucent fill, solid edge) or, cumulative, stacked areas (translucent fill, solid edge) under one dashed cost line, people on the categorical `CATEGORY_PALETTE` by position and
  NUMBERED ("2. Bob", `personLabel`; the page's own `ChartLegendList` from two people, the tooltip "2. Bob · delivered" / "· delivered, cumulative"), so identity never rests on colour. The tooltip adds the slot's items ("5 items", cumulative "5 items so far"). Tables carry every number: the bucket table (an Actual cost (MD) column; cumulative view: three more columns), the sprint table (the same), and in people mode `ThroughputUserTables`:
  "Totals per person" (`groups`, plain numbered text — the team level's "By member" drill table is replaced), and with two or more people "Each person's share of each period" and "of each sprint" (one row per slot and person, running totals in the cumulative view).
  **The period card drills** ("Drill to items" below): a double-click on a bar — in people mode on one person's segment — or a click on a row's **Delivered** figure (MD or items; the bucket table in BOTH chart views, and each person's row of "Each person's share of each period") opens `ThroughputDrillDrawer` with the tasks behind that week/month (`GET /reports/throughput/items`); the cumulative areas are not bars and have no double-click, **the sprint card drills too**: a double-click on a sprint's bar (in people mode one person's segment) or a click on a sprint row's **Delivered** figure (`DELIVERED`; the sprint share table's rows likewise) opens `SprintDrillDrawer` with the tasks that sprint delivered (`GET /reports/throughput/items` with the period narrowed to that `sprintId` and no `bucketStart` -- the server's sprint mode, which drops the activity/category slice the sprint view does not carry), the card's own `drillAnchor` (`throughput-sprint`) being its focus-return target (`useThroughputDrill` owns the handlers, `open(target, { trigger, anchor })` names the card; by default the hook's anchor, which may be a function of the bar, so a deep-linked sprint bar returns focus to the sprint card). Chart rows carry an `id` (a bucket's first day; a sprint's `<teamId>:<sprintId>`), which is what the drill is keyed by; the drawer is compared with the row the chart drew (a segment with that person's `byUser` count).
  **Actual cost** (1.10.0; `.claude/docs/reports.md` "Report 2" -> "Actual cost", `actualCostMd` on every `byBucket`/`bySprint`/`byUser` entry): in BOTH cards a gray bar (`CHART_COLORS.actualCost`, the EVM/Deep dive cost gray -- no new hue) stands beside Delivered in the same MD axis, so a whole-team chart has two grouped series and the chart's own legend ("Delivered", "Actual cost"); the page has no units switch (the chart is MD, the tables carry the items), and AC has no item count, so every table just gains one **Actual cost (MD)** column after the Delivered pair (bucket table, sprint table, each person's share tables; `throughputCostColumn`, `throughputCostDrillColumn`). A dimmed caption under each card's summary says what it is (`ThroughputCostCaption`: the time logged inside the period on the items not yet done when it began -- delivered in it or still open; for a sprint the time between its dates on its final scope), plus, with people picked, that AC is split by who LOGGED the time while Delivered is by assignee. *People mode*: two stacks per slot (series `stackId` `delivered` / `cost`, the chart's `type` stays default), a segment per person in each, the cost stack the translucent fill inside a solid edge (Commitments' committed-stack idiom, `DEEP_DIVE_FILL_ALPHA`); a person's cost key is `ThroughputPerson.costKey` (`a<i>`) beside `key` (`p<i>`); the page's numbered legend adds the two stacks' neutral treatment glyphs. *Cumulative view*: AC accumulates like Delivered (`cumulativeCostMd`, running per person too, and a column "Actual cost, cumulative (MD)"), drawn as ONE dashed unfilled gray line (the picked people's summed time) over the delivered areas, which stack through `areaProps` `stackId`; a per-person cost line would bury the chart (the share tables carry the per-person running cost). **The cost drills** (`ThroughputCostDrillDrawer`, `GET /reports/throughput/items?measure=ACTUAL_COST`, `getThroughputCostItems`): the target kinds `ac-bucket:<monday>` and `ac-sprint:<teamId>:<sprintId>` (+ `;s=<accountId>`) -- a double-click on the `ac` bar (or a person's `a<i>` segment), or a click on a row's Actual cost figure (a zero figure is plain text) opens a drawer titled "Actual cost · <week | sprint>", one row per TASK with the MD logged on it (the `loggedMd` unit: Authors, Last logged, Logged (MD)), whose server `bar.sumMd` is held against the chart's `actualCostMd` (`ReportDrillDrawer`'s `chartMd`: half a cent) and shows the changed-data note on a difference. Focus returns like the delivered drills (`PERIOD_DRILL_ANCHOR` / `SPRINT_DRILL_ANCHOR`). Tests: `ReportThroughputCostDrill.test.tsx`, `ThroughputCostDrillDrawer.test.tsx`.
  **By epic** (`components/ThroughputEpicSection.tsx`, the same `SprintEpicSection` as Commitments; `.claude/docs/reports.md` "Report 2", `bySprint[].byEpic`): a card after the sprint card, "Delivered per sprint, by epic" — ONE solid stack per sprint (delivered), the same segments, colours, legend, table ("Delivered scope per sprint and epic": Sprint · Epic · Delivered (MD) · Delivered (items)), rule note and drill as Commitments' (`sprint:<teamId>:<sprintId>:DELIVERED:e=<key>` → `GET /reports/throughput/items?sprintId=…&teamId=…&epicSegment=…[&topEpicId=…]`, the sprint mode; `throughputSprintDrillCount` compares the segment's items). **Only the sprint view is split**: the period card (`byBucket`) has no by-epic chart or table, and the section stays through the Per period / Cumulative switch (its bars are per-sprint figures either way). Focus returns to `data-drill-anchor="throughput-epics"` (`EPIC_DRILL_ANCHOR` in `useThroughputDrill`). Tests: `ReportThroughputEpics.test.tsx`.

- **Distributions** (`components/DistributionPanel.tsx`): a `Distribution` renders as a percentile
  strip (median p50, p90, p95, mean, item count), a lazy `DistributionHistogram` (single blue series,
  no legend — but see "The long tail" below) and the histogram as a table; `hidden` (fewer items than `meta.minSampleSize`)
  replaces all of it with `MinSampleNotice` (gray, names n and the minimum — never a warning: a small
  sample is a fact about the selection). Units come in through `format`: accuracy ratios via
  `formatRatio` ("actual ÷ estimate", 1.00 = on estimate), fractional changes via
  `formatSignedPercent` (`+25%`, a true minus sign), shares via `formatPercent`. A group below the
  minimum shows its counts and a dash (`formatMedian`), never a median. `ExcludedList` is the counted, plain-language accounting of ONE distribution — rendered under the
  distribution it describes, never merged across views, because the two views' partitions differ
  (`n + Σ reasons == population`, spelled out as a closing equation line; the server's per-view
  partition, pinned on the fixtures by `reportFixtures.test.ts`, like A17). Histogram range labels
  come from `histogramLabels` (widened a decimal at a time until no two ranges print alike — no
  "1 – 1") and the x axis is titled with what the ranges measure (`axisLabel`). Numbers are never
  locale-formatted (repo convention): the ratio hint says "1", like the UI prints. Epic figures at USER
  level are one `EpicsPerPersonNote` line (epics carry no user), not an empty state or zeros. Heading
  levels: card title h3, block titles h4, distribution and accounting titles h5.
  **The long tail** (`tailPercent`, server: `.claude/docs/reports.md` "The long tail"). A histogram ends in an open bucket (`to: null`, the values above the (100 − N)th percentile, roughly the top N% of the
  values) that `histogramLabels` prints "≥ X". `components/ReportTailControl.tsx` is the ONE control, on the eight reports whose response carries a
  `Distribution` (task/epic accuracy, task estimates, adjustments, cycle time, reported time, blocked time, data quality): a "Long tail" `Select` (`ReportFilterSelect required`,
  0/1/2/5/10/15/20/25 %, a hand-edited in-range value joins the list) plus the help line, placed right under the meta note and above the first card — page-level,
  NOT in the filter bar and not in each `DistributionPanel`, because one answer feeds two to six histograms per page and the value is page-wide (it is no
  narrowing either, so `activeReportFilterCount` ignores it). It IS a report-specific managed param (`ReportFilterState.tailPercent`, in `MANAGED_KEYS`
  and `REPORT_SPECIFIC_KEYS`, kept per report by `REPORT_SPECIFIC_PARAMS`): in the URL, the request and the query key, and dropped across tabs to a report
  without a histogram. The default `DEFAULT_TAIL_PERCENT` (5) is never stored — parsing drops it, the control writes it as an absent param, so
  the default URL, request and key are the bare ones; `0` is real (the plain histogram) and an integer outside 0..25 is dropped like any invalid param.
  The open bucket is its own series in `DistributionHistogram`: rows carry `open`, the chart stacks a second gray series (`CHART_COLORS.longTail`,
  `gray.6`, 3:1 pinned by `chartColors.test.ts`) named "Long tail (top values)" beside the estimate-blue body, with a legend only when a tail exists and the series
  a bar does not belong to left `undefined` (never 0, so the tooltip lists one series); colour is never the only carrier — the x label starts "≥", the legend and
  tooltip name the series, and the histogram table's range cell reads "≥ X (long tail)" (`reports.distribution.tailRow`). The counts still sum to `n`.
  Task and epic accuracy show the at-start view first (D15: the primary view) and the at-done view
  beside it; an epic's own estimate is never replaced by its child sum (both are columns). The epic
  accuracy page offers team (required), domain and work category (an epic's domain is its own space under either
  domain view, and it has no activity type; it has no member control either, see below); the other two estimation pages offer the domain view too.
  **Adjustments is read per team and takes several people** (`pages/ReportEstimateAdjustments.tsx`; `CONTROLS` = `teamRequired`, `multiMember`, domain view,
  domain, activity type, work category; the no-teams `EmptyState`, the normalization and the 8-person cap are Throughput's): at `USER` level the page gives
  the TASKS block's change histogram its OWN people (`distributionPeople(report.tasks.changeDistribution)`, forwarded by `AdjustmentsBlock`'s `people` prop to
  `DistributionPanel`) for the stacked histogram and its table column per person, and `ReportPersonTotals` replaces the by-team/by-member drill table (the page's
  own tasks/epics columns; the epics ones read "—"). The started counts, the share and the exclusions stay over everyone picked together, and the epics
  block stays the `EpicsPerPersonNote` (epics carry no user, so they are never split). The exclusion list under each block
  (`ExcludedList`) shows the estimates entered only after the item was done (A35, `changeExcluded.estimatedAfterDone`) as an "Of these, N: ..."
  line under the unestimated bucket (`ExcludedItem.ofWhich`): a breakout that stays out of the closing equation. The "Estimated late"
  note also says that only changes made while the work was going on count. The change histogram charts only the items whose estimate changed:
  `AdjustmentsBlock` shows the unchanged ones as a "Not charted: N finished items with an unchanged estimate" line under the chart (shown even while the
  histogram is withheld, hidden at 0; when `n` is 0 but items are unchanged the panel is replaced by "No estimate changed: …") and as the first line of the exclusion list, so `n + unchanged + late + unestimated = population` still adds up.
- **Cycle time and reported time**: `DistributionWithAccounting` (panel + its own `n + Σ = population`
  list) is the unit every distribution page composes. Cycle time shows working days (primary) and
  elapsed days, then the trend — a `LineChart` of p50 (solid `flow.6`) and p90 (`gray.6`, DASHED, so
  the two lines differ by dash as well as hue: the legend swatches are hue-only, so the dash, the trend
  table's column headers and the tooltip carry identity) per week/month, reusing throughput's bucket control and its
  "the report travels with the bucket that produced it" rule. A bucket below the minimum sample has
  `null` p50/p90: the line BREAKS there (`connectNulls` off — a gap, never a zero) and the trend
  table beside it prints a dash but keeps that bucket's real `n`; if no bucket is plottable a note
  replaces the empty frame. Below the task views and their groups table sits `components/CycleTimeEpics.tsx`:
  the epics finished in the period (the report's `epics` block) as the same two views — working days first —
  each with its own accounting, then, at UNIT level, an "Epics by owner team" table (`ReportGroupsTable`'s
  `wording` prop: its own title, "Owner team" header and "No owner team" for a null team; the row name still
  narrows `teamId`). No finished epic is a one-line note, and USER level is the `EpicsPerPersonNote`.
  **Cycle time is read per team and takes several people**, exactly like Reported time below (`CONTROLS` = `teamRequired`, `multiMember`, domain view, domain,
  activity type, work category, bucket; the no-teams `EmptyState`, the normalization and the 8-person cap are Throughput's): at `USER` level the
  page gives each of the working-days and elapsed-days distributions its OWN people (`distributionPeople`) for the stacked histogram and its table
  column per person, and `ReportPersonTotals` replaces the by-team/by-member drill table. **The trend card is untouched** — one `p50`/`p90` line over everyone picked
  together — and so are the percentile strips, the accountings and the epics note. The server takes a `teamId` or a `domain` and refuses a request naming neither (`reports.md` "Scope"); Home makes no such read, it reads `/reports/home`.
  **Task accuracy is read per team and takes several people too**, with Cycle time's wiring (`CONTROLS` = `teamRequired`, `multiMember`, domain view, domain,
  activity type, work category; the no-teams `EmptyState`, `MULTI_ACCOUNT_PATHS`, `distributionPeople` per view, `ReportPersonTotals` at `USER` level). Its two
  panels pass `strip="centre"` to `DistributionPanel` — the strip shows the median, the mean and the item count only, since p90/p95 say nothing about
  accuracy (cycle time and the other distribution reports keep the default `full` strip) — and `accuracy`, which renders
  `components/AccuracyMeasuresTable.tsx` between the strip and the histogram: "Accuracy measures — <view title>", Measure · Value (two columns),
  nine rows (PRED(10/25/50), MdMRE, sMAPE as percentages via `formatPercent`; IQR, IDR, MAD as ratios via `formatRatio`; the tail share beyond ±2σ as a
  percentage, "—" when the server sends null), each with its one-line meaning (`reports.accuracy.meaning.*`) on demand behind a small info `ActionIcon` beside the measure name (`aria-label` "About {measure}"): a `Tooltip` opening on hover, keyboard focus and touch, so the rows stay compact; the open tooltip is the icon's `aria-describedby`. The measures are over the whole selection
  (never per person); a hidden view has neither strip nor table.
  **Task estimates** (`pages/ReportTaskEstimates.tsx`, route `/reports/task-estimates`, the LAST Estimation tab; `.claude/docs/reports.md` "Report 19") is Task accuracy's
  copy for the estimates themselves: the same `CONTROLS` (`teamRequired`, `multiMember`, domain view, domain, activity type, work category), the no-teams `EmptyState`,
  `MULTI_ACCOUNT_PATHS`, `distributionPeople` per view and `ReportPersonTotals` at `USER` level, and its `REPORT_SPECIFIC_PARAMS` row equals Task accuracy's. Differences: the
  AT-DONE panel comes first (the primary view), the default `full` strip (p50/p90/p95/mean/items -- the spread of the estimates is the point) and no accuracy
  measures, MD formatting (`formatMd`), the axis "estimate (MD)", and each view's accounting lists only its own exclusions (no "no time logged": at done the
  unestimated tasks, at start the never-started and the unestimated). The group table reads Finished · At done (items) · At done (median MD) · At start (items) · At start (median MD).
  **Fixed-width histograms drive their own labels**: `histogramLabels(buckets, format, bucketWidth)` (`utils/reportFormat.ts`, called by `DistributionPanel` and
  `DistributionDrillDrawer` with `distribution.bucketWidth`) prints a bar of a `bucketWidth` response by its UPPER bound alone ("0.5", "1", "1.5"; the bar is `(from, to]`,
  the open tail "> X", the values strictly above X, where an equal-width tail reads "≥ X") and never "from – to"; the long-tail control's help says "> X" too (`ReportTailControl strictTail`, `reports.distribution.tail.helpStrict`); the first table column is headed "Up to (MD)" (`reports.distribution.upTo`) instead of "Range" in this mode -- the response, not the page, selects the mode, so the table's range cell, the drill drawer's subtitle ("Estimate at done · 1")
  and the chart's x labels agree. The drill is `getTaskEstimatesItems` with `view` `AT_DONE`/`AT_START` and the `estimateMd` figure unit (header "Estimate (MD)").
  Tests: `ReportTaskEstimates.test.tsx`, `ReportTaskEstimatesDrill.test.tsx`, `reportFormat.test.ts`, the `TASK_ESTIMATES*` fixtures and their partition test in `reportFixtures.test.ts`.
  **Epic accuracy is read per team, never per person** (`pages/ReportEpicAccuracy.tsx`; `CONTROLS` = `teamRequired`, `noMember`, domain, work category):
  the Team select has no "All teams" (the no-teams `EmptyState`, `useReportPage` holding the query until a team is set), there is no Member control, and the
  page's `normalize` is `dropMember` (`utils/reportFilter.ts`, deletes `accountId` / `accountIds`), so a pasted link's member is dropped and the URL
  rewritten; `NO_MEMBER_PATHS` (`utils/reportLinks.ts`: sprint consistency, epic accuracy, epic progress) makes `reportHref` drop the `accountId`s on the way in from a
  tab. Both panels pass `strip="centre"` and `accuracy` (Task accuracy's `AccuracyMeasuresTable`, the same nine rows over the epics' own ratios). The server keeps `teamId`
  optional (D1), and the owner-team "By team" drill and `EpicsPerPersonNote` are no longer reachable on this page (a team is always picked, an epic has no user).
  Consequently, epics with no owner team (the server's `teamId = 0`, the "No owner team" group) cannot be reached from this report at all: it is the accepted price of requiring a team.
  Reported time explains in its description that the ratio is "how much of
  the elapsed working time was logged" (actual MD ÷ cycle working days), distinct from flow
  efficiency (active ÷ cycle time, shown as %, no "no time logged" bucket — so the two panels keep
  separate accountings), and reads the median first (the outlier note: very short cycles dominate
  the mean, p95 and the histogram's top; they are never dropped).
  **Reported time is read per team and takes several people** (`CONTROLS` = `teamRequired`, `multiMember`, domain view, domain, activity type,
  work category; the no-teams `EmptyState`, the normalization and the 8-person cap are Throughput's). The page shows what the REPORT's
  `meta.level` says: `USER` = **people mode**. `utils/distributionPeople.ts` (pure, tested) turns a distribution's `byUser` into the numbered
  people (`distributionPeople`, 1-based in request order) and the histogram rows with each bucket's per-person counts
  (`histogramRowsWithShares`); the page passes each of the two distributions its OWN people to `DistributionWithAccounting` → `DistributionPanel`
  → `DistributionHistogram`. People mode stacks one outlined segment per person on the categorical `CATEGORY_PALETTE` (the open tail bucket split too, so no
  gray tail series), numbers people ("2. Bob") in the page legend (from two people), the tooltip and the table, whose histogram table gains a
  right-aligned column per person beside the total; with no `people` every panel renders as before. `ReportPersonTotals` (generic over
  `GroupIdentity`) is the USER-level "Totals per person" table over `groups` with the page's own `GroupColumn`s, replacing the drill table. The
  percentile strip, the exclusion lists and the group table stay over the union / per pick, never stacked.
  **Distribution drill** (opt-in per consumer; today Cycle time's four histograms, Reported time's two, Task accuracy's two, Task estimates' two, Epic accuracy's two, Blocked time's two and Estimate adjustments' two change histograms): `DistributionWithAccounting`/`DistributionPanel` take `onDrill(bucketIndex, segmentAccountId?)`, and only then does the histogram get
  `onBarDoubleClick` (rows carry `id` = `String(index)`; series `count`/`tail` = the whole bucket, `p<i>` = `people[i]`), the histogram table's **Items** count of each bucket becomes a button ("… show the items of <histogram>: <range>"; in people mode each person's count is one too, "… · <person>", opening that person's segment; a zero count is plain text; there is no separate Items column) and the dimmed
  hint appears — a page or block that passes no `onDrill` (Data quality's late-logging histogram) shows none of it. The target is `{ kind: "hist", view, bucketIndex, segmentAccountId? }` (URL `drill=hist:WORKING_DAYS:3`, `view` = the server's own
  name); `components/DistributionDrillDrawer.tsx` is the shared drawer: the page hands it the histograms it drew as `views` (`view`, title, the shown `Distribution`, `format`, figure `unit` — `wd`/`days`/`ratio`/`share` and the accuracy pages' `accuracy` ("Actual ÷ estimate") and Blocked time's `blockedWd`/`blockedShare`, their own column headers — and its people), so the drawer is headed with the histogram's title and the table's range label as the dimmed subtitle ("Working days" over "2 – 4"; the dialog's name "Working days · 2 – 4"),
  compares the server's count with the histogram's (a person's with their `byUser` count) and fetches `getCycleTimeItems`/`getReportedTimeItems`/`getTaskAccuracyItems`/`getTaskEstimatesItems`/`getEpicAccuracyItems`/`getBlockedTimeItems`/`getEstimateAdjustmentsItems` with the SAME filter as the shown report (Blocked time always sends its `itemKind`, like its report request; Epic accuracy and Blocked time pass no people, so no segment). The items table drops "Unassigned" for an epic (a dash: epics have no assignee) and adds a Kind column to EVERY bar of a request that may mix tasks and epics (`itemKind=BOTH`, `showKind` from the request's filter — never from the current bucket's rows, so the columns do not shift between buckets). A `drill` naming a bar the SHOWN report did not draw (another report's view, a hidden or empty distribution such as the epics block at USER level, an index past the last bucket) opens nothing and, once the report is in hand, is dropped from the URL (a replace) — never a drawer that answers with a 400. The hint under the histograms (`reports.drill.hint`) tells the reader to click a figure in the table. Cycle time's epics card has its own focus-return anchor
  (`useReportDrill`'s `open(target, returnTo)`), the tasks card the default one. **Adjustments** (`pages/ReportEstimateAdjustments.tsx`): `AdjustmentsBlock` takes `onDrill` and
  forwards it to its change `DistributionPanel`, the page wires `TASKS` (with the picked people, so a person's segment is a `segmentAccountId`) and `EPICS` (none at USER level,
  where the block is the per-person note) to `getEstimateAdjustmentsItems` under ONE card anchor. The unit is `change` (a signed percentage, header "Change in estimate"), and
  the drawer title names the block ("Tasks — Change from start to done" over "+10% – +25%") because both blocks share the panel title. Only the ADJUSTED items are charted, so the
  states that draw no histogram stay as they were and offer no drill: the "No estimate changed" notice, a hidden histogram (below the minimum sample) and the epics block at
  USER level; the "Not charted: N …" line and the accounting beside the chart are untouched, and a `drill` naming such a bar opens nothing and is dropped.
- **Flow metrics group** (`pages/ReportWip.tsx`, `pages/ReportBacklog.tsx`, `FLOW_TABS`, the "Flow metrics" nav leaf
  `appShell.nav.reportsFlow` — deliberately not just "Flow", which the brand text already is): the two
  snapshot reports over the daily aggregates. Their bar offers only `domain` (never activity type/work category —
  `400` server-side) and `domainExcludesTeam` makes the LAST of team/domain touched win (the aggregate has no
  team × domain split; picking a domain therefore clears the team), and a
  pasted link with both is normalised on load — the team wins — by `useReportPage`'s `normalize` argument
  (`dropDomainWithTeam`, `normalizeWipFilter`), which rewrites the URL too so a dropped param cannot come
  back when another control changes. **WIP is never read for the whole unit: exactly ONE of a specific team or a specific
  domain** (`CONTROLS = { teamRequired, teamOrDomain, domainExcludesTeam, … }`; the server now refuses a read naming neither — `reports.md` "Scope" — the `teamRequired` precedent). `teamRequired` gives a link naming neither the default team (`requiredTeamId`: the sprint's
  team, the remembered team, else the first by name; `teamId=0` counts as absent) and removes "All teams" and the clear button;
  `teamOrDomain` (`ReportControls`, `domainStandsForTeam`) lets a domain stand in for the team: with a domain and no team the page is valid
  and queried (`useReportPage` enables it), the team select shows no team (placeholder "No team: a domain is picked", still not
  clearable), and the remembered team is NOT wiped by the domain pick (`useReportFilter.setFilter`), so clearing the domain returns to it.
  The domain select stays clearable; picking a team clears the domain. With neither team nor domain in the unit the page is the
  "nothing to read" empty state, with domains but no team the bar stays up over a "pick a team or a domain" note (no request).
  **Estimated backlog is read the same way** (`CONTROLS = { teamRequired, teamOrDomain, domainExcludesTeam, noMember, domain }`; the same
  empty state and `pickScope` note under `reports.backlog.*`), and so is **Epic progress** (below). WIP adds two
  managed params, `by` (stage · status · board column) and `itemKind` (tasks · epics; never both summed -- `ReportControls.itemKind` lists the kinds a page offers, blocked time adds `BOTH`), both always SENT
  explicitly (`by=STAGE&itemKind=TASK` by default; a link or stored `itemKind=BOTH` is dropped by `normalizeWipFilter`, falling back to TASK); `by=COLUMN` needs one team (`wipColumnAvailable`) so the
  option is disabled with a hint otherwise and a stray `by=COLUMN` is dropped from the URL (falls back to
  STAGE), and a `400` for a request that WAS for columns of one team is the "no board mapped" note, not a
  failure (any other 400 is the normal failure alert). The chart is a stacked `AreaChart` of end-of-day counts with band
  toggles (`Chip.Group`, local state): stages keep their vocabulary colours (gray/blue/**grape = Waiting**, `CHART_COLORS.stageWaiting` grape.6, visible by default/**red = Blocked**, `CHART_COLORS.stageBlocked` red.7 — the very shade of `dropped`, so no new hue — also visible by default (A36: an impediment is exactly what the chart should not hide)/teal/orange; band order Not started, In progress, Waiting, Blocked, Done, Unmapped is the server's `keys`); statuses
  and columns are arbitrary names, so they never wear a semantic hue: `CATEGORY_PALETTE` is a seven-hue categorical
  palette (flow.6, violet.5, cyan.7, indigo.5, pink.6, gray.6, lime.9 — one shade per hue, each clearing 3:1 on all four surfaces, in an order that keeps
  every neighbour at least ΔE2000 22.9 apart; `chartColors.ts` has the figures and `chartColors.test.ts` recomputes them), assigned by
  position among the bands SHOWN (`paintBands`) so neighbours are always distinct; from the eighth band the palette repeats and the legend, tooltip and tables carry identity. The ticked-bands state is per keying (and per team
  for columns); STAGE starts with Not started and Done hidden (Done only
  grows, Not started is the whole backlog). Its text alternatives are a per-band summary table and the full
  daily table behind `DailyTableDisclosure` (a series can run to ~1100 rows). **WIP takes people** (`CONTROLS.multiMember`, `wipPath` in `MULTI_ACCOUNT_PATHS`; the Member control is the same multi-select as cycle time's, up to eight, offered
  under a team and so never in a domain read): the server computes a person's WIP at query time inside the picked team (`reports.md` "Report 9",
  USER level) and each point carries `byUser`. The chart stays the union stacked by band (a note under the toggle says whose tasks it counts and that epics are left out;
  `EPIC` shows the server's `note`); from TWO people a `SegmentedControl` (page-local state, "By band" · "By person") adds the per-person view — the same `WipChart` with one stack
  segment per person (`utils/wipReport.ts`: `wipPeople`, `paintPeople`, `wipPeopleChartRows`), each segment that person's items over the TICKED bands, people numbered "2. Bob" on the categorical
  `CATEGORY_PALETTE` (their number, not the hue, carries identity — Commitments' and Throughput's rule). The text alternatives follow the view: per-person summary
  (`WipPeopleSummaryTable`) and daily table (`WipPeopleDailyTable`, a column per person) in person view, the band tables otherwise. One person IS the union, so there is no view switch. The backlog page is three tiles
  (MD, items, "≈ N sprints ahead" — a dash plus the reason when there is no pace (no delivered mean) or the mean is 0,
  "< 0.1" instead of "≈ 0"; at UNIT level the wording says "at least N closed sprints per team", since the pace
  is a sum of team means and `sprintsUsed` their minimum) and a
  one-series MD `AreaChart` whose tooltip and table add the item count. **The backlog is owned by a team, so its page has no Member control**
  (`CONTROLS.noMember`; `backlogPath` in `NO_MEMBER_PATHS`; `dropMember` in `normalizeBacklogFilter`, so a pasted `accountId` is dropped from the
  request and the URL like Sprint consistency's and Epic accuracy's — the API itself still answers a member with an empty block and a note). **The
  staleness slider** (`components/BacklogStalenessControl.tsx`, between the notes and the tiles, outside the filter bar like `ReportTailControl`,
  A38) narrows every trend day to the items someone touched within N days of it: a Mantine `Slider` over the INDEX of eight stops (7 · 14 · 30 · 60 ·
  90 · 180 · 365 · No limit, `restrictToMarks`; the label and `aria-valuetext` read "90 days (default)" / "No limit"), committed on `onChangeEnd`
  only (a drag or one key press = one request; `onChange` just moves the thumb and its label). The managed param is `touchedWithinDays`
  (`REPORT_SPECIFIC_KEYS`, `reportSpecificKeep(backlogPath)`): absent = the page default 90, **sent explicitly** by the fetch lambda (the URL stays
  bare for it, the WIP `by` precedent), `0` = No limit and is kept in the URL; a hand-edited value off the steps (or a pasted 90) is snapped by
  `snapBacklogStaleness` (`snapToStalenessStep`: nearest step, a tie to the larger) and the URL rewritten. The Items tile carries the as-of-day
  note — "N items (X MD) hidden: no change in the last N days." from `current.hiddenItems`/`hiddenMd`, "Nothing hidden…" when the window is on and
  hides nothing, nothing when it is off (`touchedWithinDays` null); the per-day `hiddenItems`/`hiddenMd` are in the response but not shown yet. The
  slider box is `w="100%" maw={400}` with side padding, so the end marks and the value tooltip never widen the page at phone width. The server's `note` (not derived yet,
  USER level, …) is prose in English only: `ReportNote` shows it verbatim under a translated title, except the "Not derived yet" note when
  `meta.derivedAt` is null (the translated meta line already says it).
- **Aging WIP and blocked time** (`pages/ReportAgingWip.tsx`, `pages/ReportBlockedTime.tsx`, tabs 3 and 4 of
  `FLOW_TABS`). **Both are read per team, never the whole unit** (`CONTROLS.teamRequired`, passed to `useReportPage` too; the server's `teamId` stays
  optional): the Team select has no "All teams", a link or a stored filter without a team is given the default team (`requiredTeamId`) and
  the URL rewritten, no teams at all is the `noTeams` `EmptyState` and no request; a domain is still picked beside the team (no `domainExcludesTeam`).
  Aging WIP is "as of now": its bar passes `noPeriod` (no period control) and the request drops
  any period param (`withPeriod(filter, {})`; the URL keeps it so tab hops preserve it). **One page-level Tasks | Epics switch**
  (a `SegmentedControl` under the filter bar and the meta note, the WIP `itemKind` labels; `hooks/useAgingKind.ts`) picks the kind the whole page
  shows — thresholds, chart and table — because tasks and epics age on different scales. The choice is the page-local URL param `kind=epics`
  (`AGING_KIND_PARAM`, `reportLinks.ts` `PAGE_PARAMS`: no filter, never in the request or the query key, so a switch refetches nothing, and it
  follows a tab link only to this page); Tasks is the default and the absent param, and any other value reads as Tasks. The switch is HIDDEN
  (not disabled) where epics are not offered (`utils/agingReport.ts`'s `offersEpics`: not at USER level, and only when the response lists an
  epic or has an epic window), and a `kind=epics` link there reads as Tasks. A kind with nothing in progress says so
  ("No epic is in progress right now.") and drops its chart card. It shows the
  thresholds as one tile row for the chosen kind (`AgingThresholdsRow`; hidden
  thresholds = a `MinSampleNotice` with `subject="thresholds"`) above the open-work table (that kind's items only, so there is no "Kind" column), in the server's
  order (never re-sorted). **The band is the SERVER's** (`"P85"`, `"WITHIN"`, `null` = hidden) — never
  recomputed from the age; `utils/agingReport.ts`'s `bandTone` only reads its RANK among the configured
  thresholds of the item's own kind: the top threshold red ("above p95"), the next orange ("above p85"), the
  rest gray — the text always names the threshold, colour is never the only carrier. Blocked is a red outline
  badge with the word (`item.blocked`: a Flagged or BLOCKED-stage item, A36); Waiting (`item.waiting`, A30) is a light grape badge with the word in its own "Waiting"
  column, rendered only when some listed item waits (waiting items keep aging). A BLOCKED-stage item shows the Blocked badge and is never read as
  waiting (`waiting` is false for it, the chart's hollow dot keys on `stage === "WAITING"`).
  **The aging chart** (`components/AgingChartSection.tsx` — the card body: log switch, notice, legend, truncation note — over the lazy
  `AgingScatterChart.tsx`, `ScatterChart` of `@mantine/charts`; pure logic in `utils/agingChart.ts`, marks in `AgingDotMark.tsx`) is a
  `ReportChartCard` between the thresholds and the table, absent when nothing is in progress: one dot per listed item, x = where it is in the
  workflow (the report's `positions`, in the server's order, never re-sorted: a team's board columns or the statuses), y = its age in working
  days from 0, extended (6% headroom, rounded up) to the oldest item or the highest threshold — **an outlier is never clipped**; a "Log scale" `Switch`
  (off by default, remembered per viewer under `flow.viewSettings.reports.agingWip.logScale`) turns the y axis logarithmic from 0.5 working days
  with 1-2-5 ticks — ages under half a day are PLOTTED on the 0.5 line (tooltip and table keep the true age, the axis label and a note say the scale). **Tasks and epics are never on one plot** (different threshold scales): the chart takes the page's chosen `kind` (it has no switch of its own), and each plot has only the
  positions its own items hold. The x axis is a NUMBER axis (the category index plus a deterministic jitter inside the category band —
  `jitterOf`, an FNV-1a hash of `itemKind:issueKey`, never `Math.random()`) whose ticks are the category indexes, labelled by `tickFormatter`
  with the position names (shortened to 12 characters from 7 categories on; the tooltip and table carry the full name) — the category-axis
  route `@mantine/charts` does not offer, since the library hard-codes `type="number"`. The thresholds are dashed `referenceLines`, one per
  configured percentile, labelled "p85 · 12 wd" in the TEXT colour (the line wears its band's hue by rank: gray / orange / red); hidden
  thresholds draw no line and show the `MinSampleNotice` above the chart, the dots still render (gray, no band). **Dots** (a custom
  `scatterProps.shape`): the server's `band` read through `bandTone` — red.7 the top threshold, orange.8 the next, gray.6 the rest, from
  `chartColors.ts`, no new hue — as a 12 px dot with a 2 px surface ring; **waiting** is a second channel, a HOLLOW dot (surface fill, band-coloured
  outline); **blocked** a red outer ring with a gap; the selected dot a thin text-colour ring; each dot has a ~26 px transparent hit target. A page-built
  legend (`ChartLegendList`) names the bands by threshold ("Above p95", "Above p85", "At or below p85"), hollow = waiting, outer ring = blocked; the
  tooltip names key, summary, position, age, band, assignee, waiting and blocked in words. **Clicking a dot** selects it (page state, a second click
  lets go): `AgingItemsTable` marks that row (`data-selected`, the primary tint) and scrolls it into view. The dots are a pointer affordance only;
  the keyboard and screen-reader route is the table, which is the chart's text alternative (every item, every number). A truncated list is plotted
  as listed and repeats the truncation note. Home's WIP charts count the same open
  items (the overview's `wipTasks.count`, waiting and blocked ones included). Flow efficiency's caption on Reported time
  says time in a waiting or blocked status counts as waiting. Issue keys link to Jira ("Jira links" below). Blocked time composes
  `DistributionWithAccounting` twice (both over the BLOCKED items only: blocked working days with `neverBlocked` as its one
  accounting reason plus an "N of M items were never blocked" line; share of cycle with `neverBlocked`/`neverStarted`/`zeroCycle`), the top-20 table and the
  groups table; `itemKind` (default TASK, always sent) reuses the WIP bar control with a third `BOTH` option (`BLOCKED_ITEM_KINDS`), and at TEAM level with epics
  a line says the per-assignee groups cover tasks only.
- **Epic progress (EVM)** (`pages/ReportEpicProgress.tsx`, the fifth tab of `FLOW_TABS`; `.claude/docs/reports.md` "Report 15").
  The URL carries at most ONE scope — `epicId` (an epic's issue key, a report-specific managed param that only this
  report keeps), `domain` or `teamId` (`0` = UNASSIGNED) — and `normalizeEpicProgressFilter` (`utils/epicProgressReport.ts`,
  the `useReportPage` normalize hook) makes a pasted link answerable: epic over domain over team, and `domainView`,
  `accountId`, `activityType`, `workCategory` (each a `400`) plus the ignored `bucket`/`by`/`itemKind`
  dropped — off the request and off the URL. The bar passes `domain` + `domainExcludesTeam` + `noMember` (no user
  level) and the page routes its changes through `applyBarChange`, so the LAST scope touched wins, epic included. **It is never read for the
  whole unit**: exactly ONE of a team, a domain or a single epic (`CONTROLS = { teamRequired, teamOrDomain, epicScope, domain, domainExcludesTeam,
  noMember }`, WIP's rule plus `epicScope`): `epicScope` lets a URL naming `epicId` (reached from the page's own domain rows) stand in for the team
  exactly as a domain does — `domainStandsForTeam` is true, no default team is added, `useReportPage` fetches it, and the team select shows
  "No team: a single epic is shown" — while a team picked from the bar replaces the epic (`applyBarChange`). A bare link or stored filter with none
  of the three gets the default team; with neither team nor domain in the unit (and no epic) the page is the `noTeams` empty state, with domains
  but no team the `pickScope` note. A request naming no epic, domain or team is `400` (`reports.md` "Scope") and the wire `level` has no unit value, so `EpicProgressRows` draws only a domain's epics (EPIC and TEAM have nothing further). The print summary names a single epic ("Epic: FLO-33 (stands for the team)"), as it does a domain.
  Levels are drilled by the row NAME links (`scopedSearch` re-scopes the same report, the period travelling along); a
  domain → epic drill carries the domain in the router `state` so the breadcrumb (`EpicProgressBreadcrumb`) can offer the
  way back — there is no unit crumb (the unit is not a view of this page), so the breadcrumb exists only for an epic reached through its domain,
  and a directly opened epic has none. **The basis (A37):** a segmented control "This period | Since
  start" (`EpicProgressBasisControl`, in the first card above the tiles) writes `basis` — a report-specific managed filter param that only
  this report keeps (`ReportFilterState.basis`, `REPORT_SPECIFIC_KEYS`) — to the URL and the request; absent, the server defaults per level
  (one epic since its start, a domain and a team on the period) and the control shows the EFFECTIVE `report.basis`, so the default
  is never written until the user touches it. Everything on the page follows `report.basis`: the tiles' primary value is the period block or
  `asOf` (`figuresByBasis`) with the OTHER basis as one small line under it ("Since start: 0.94" / "This period: 1.17",
  `ReportTile`'s `secondary`); a period index the server sends as `null` (under 1 MD planned or spent) is a dash plus "Too little planned/
  spent in this period"; the TEAM foreign-work share tile shows `foreignWorkSharePeriod` on the period and `foreignWorkShare` otherwise
  (the one matching the CPI shown); the MD chart and its table plot `series` as the server rebased it (its own title, caption and
  accessible names per basis; the y axis is anchored at 0 but follows a negative value down), and the drill tables show each row's
  `period` block on the period basis (a dash for a hidden index). **The trend card** ("SPI and CPI trend", present whenever `trend` is not
  empty, i.e. every level incl. a single epic) is a second, separate lazy chart (`EpicProgressTrendChart`: SPI in the plan blue, CPI in the
  cost gray DASHED — `CHART_COLORS.evmTrendSpi/Cpi`, one index axis, never a second axis on the MD chart — a labelled reference line at 1.0, a
  legend, `connectNulls` off so a hidden week is a gap) with its numbers in `EpicProgressTrendTable` beside it (newest week first); the window
  is the server's default 28 days (`TREND_WINDOW_DAYS`, the page has no control for it). Tiles: PV/EV/AC, SV/CV signed
  (`formatSignedMd`), SPI/CPI at two decimals (`formatIndex`) — a dash plus the reason when the server sends `null`
  (PV 0 / AC 0), and above/below 1 said in WORDS (`indexVerdict`, read at the printed precision), never red or green:
  an index under 1 is a fact about the plan, not a blocking failure. TEAM adds the foreign-work share beside CPI (A20).
  The UNIT drill is two tables: the domains (the epic basis — they add up to the headline) and the teams (the sprint/
  author basis — a different view, captioned as not adding up; a soft-deleted team, `active: false`, is marked "Deleted
  team" and never linked). The chart is a `LineChart` of the CUMULATIVE PV (`flow.6`), EV (`teal.8`), AC (`gray.6`) over
  ISO dates; at EPIC level the first baseline (`pvOriginal`) is a fourth series in the plan blue but DASHED, only when
  some point has it (`connectNulls` off), and the full table sits behind `DailyTableDisclosure`. The EPIC plan panel
  shows the budget and its source, the planned dates (calendar dates, read in UTC), the orange drift badges, every
  baseline (`effectiveFrom`/`supersededAt` are instants, read in the configured zone) and the two gray "no plan curve"
  notes (`!inPvHorizon` / in the horizon but `!hasPvCurve`) — EV and AC still count either way.
- **Data quality** (`pages/ReportDataQuality.tsx`, `/reports/data-quality`, its own single-page nav leaf `appShell.nav.reportsDataQuality` —
  a group of one, so no `ReportTabs` and no palette-only twin; `.claude/docs/reports.md` "Report 14"). **Read per team, never the whole unit**
  (`CONTROLS.teamRequired`: no "All teams", the default team for a link without one, the `noTeams` empty state; a domain is still picked beside the team). The
  findings that belong to no team (authors without a team, work with no team) are therefore no longer reachable from the web — a single team's view lists none (the
  server still answers unit-wide); Home's "Not in any team" note keeps its count but no longer links here, and its section-title links to Aging WIP and Data quality carry the first picked team. One card per finding
  (`components/DataQualityCard.tsx` the shell: h3 title, one plain-language line, a state badge — orange `Found: N`, teal `None found`, gray
  `Not measured`, none for the logged-hours figure — and `CappedTable`, which says "and N more" when `total > items.length`), in the order
  logging (`DataQualityLogging`) → missing data and epics (`DataQualityMissing`) → sprint/drift (`DataQualitySprint`) → configuration
  (`DataQualityConfig`, incl. A31's "Issues above the epic level" card: plain connection name, not a config link; the `info` state (no badge) because it is a figure about the model's boundary, not a finding to fix, ; its "Logged MD" column is all time, not period-bound), the overview tiles (`DataQualitySummary`, each a link that scrolls to and focuses its card — no hash in the URL)
  first and the groups table last. A clean finding keeps its card (users see it was checked). Two estimate findings (domain-model A35) sit after "Done tasks without an assignee": "Tasks estimated late" (done and open) and "Tasks estimated after they were done" (done only, `showOpen={false}`; its Estimate (MD) figure and Estimate column are the BACKFILLED estimate, since such a task has none at done), each with an overview tile and a groups-table column. The epic estimate gap card's explain text prints the tolerance the report was computed with (`report.epicEstimateGapTolerancePercent`, interpolated through `DataQualityCard`'s `explainValues`), not a fixed 25 %. The bar offers period, team/member, domain,
  domain view and — only with more than one connection — `connection` (`ReportControls.connection`); `normalizeDataQualityFilter` drops
  every param the page has no control for (activity type, work category, bucket, by, item kind, epic) off the request AND the
  URL. What the API returns is rendered as is: a real team sees no domains/authors without a team (the card says a team's view lists none),
  the connection-level findings say the team filter does not narrow them, USER level says epic and sprint findings are not read for one
  person, and a work-category field nobody configured is "Not measured", not clean. Configuration findings are admin-actionable: the
  connection cell links to `dataSourceMetricsConfigPath` for `useAdmin()` only, plain text for everyone else. Snapshot drift prints the
  sprint, translated figure, live, frozen and the signed difference (`utils/dataQualityReport.ts`), marking `reconstructed` baselines with
  an orange light badge and one explanatory line.
- **Compare people** (`pages/ReportComparePeople.tsx`, `/reports/compare-people`, nav leaf `appShell.nav.reportsCompare`, a group of one like Costs;
  `.claude/docs/reports.md` "Report 18"). Visible to everyone (D12). Two people head to head over a DATE range, across every team. *The people are the URL*: the
  repeated `accountId` read WITHOUT a team (`parseReportFilter(…, { teamless })`, `ReportControls.peopleOnly`, `COMPARE_PEOPLE_COUNT` = 2), picked beside the filter bar
  (`components/ComparePeoplePickers.tsx`: two `PersonSidePicker`s — the shared team + searchable person select, below — over `GET /jira-users?scope=UNIT`, the typed text debounced to the server as `q`; B waits for A, A is
  clearable only while B is empty, the other side is not offered, a swap button turns the comparison around). *Team pickers*: each side has its own optional, clearable **Team** select
  before its person select ("Team (A)" / "Team (B)", the report filter bar's own `filters.teams` from the page's already-cached `["reports","filters"]` query — no extra request); with a team the person select
  lists that team's CURRENT members (`GET /jira-users?scope=UNIT&teamId=<id>&sort=displayName&pageSize=100`, `teamId` in the query key) so opening it shows the whole team without typing, typing still narrows
  (`q` + `teamId`), and with none it is the unit-wide search above. The team is a picker aid only, never part of the URL or the report request, and changing it never clears an already-picked person
  (they stay in the options). Each side remembers its team per viewer under `flow.viewSettings.reports.comparePeople.teamA|teamB` (`useStoredState`; a remembered id the filters no longer list is ignored,
  and `clearRememberedFilters` forgets both at sign-out); B's team select works before A is picked, B's person select keeps its "pick A first" rule. Until both are picked the page is the pickers and an empty state and
  nothing is requested (`useReportPage` holds the query for `peopleOnly` until exactly two). `normalizeComparePeopleFilter` (`utils/comparePeopleReport.ts`) drops a team, a single
  member, a sprint-relative period, other reports' keys and a third person, and the URL is rewritten. The bar offers the date presets and the slices (domain, domain view TASK, activity
  type, work category) — no team, member or sprint period (`ReportPeriodControls datesOnly`). *Remembered filters*: the screen remembers its people and period like any report
  (`reports.compare-people`); a restore keeps the people as written (`dropStaleReferences(…, teamless)` — Jira accounts have no roster to check), and neither the shared remembered team
  nor a required team is ever applied. *Layout*: the table `ComparePeopleTable` (Metric · A · B · Difference, one `rowgroup` section per group, the rows exactly as the server lists
  them — the four capacity rows and the hint line appear iff the response carries them, NEVER from the role), the cycle-time card (two `DistributionPanel`s, working and elapsed days, `grouped`:
  `DistributionHistogram` draws one bar per person side by side, the total count column is plain text, `ReportTailControl` above) and the work-mix card (`ComparePeopleMix`: three tables of shares,
  no cue). A cell is its figure (`rowFormat` by unit) with `n = …` under it, or a dash and "below the minimum of N" when withheld. *The cue*: the better FIGURE is marked, not the difference. The two ITEMS count rows (`deliveredItems`, `wipAtEndItems`) are not sample-gated server-side, so their better cell is tinted even under the minimum sample (a count is a fact, not an estimate). A row with `better` (and a `margin` to word it) tints the `Table.Td` of that side (`data-better="true"`, `bg` the theme's `--mantine-color-teal-light`, `c` its AA-tested `--mantine-color-teal-light-color` from `LIGHT_VARIANT_INKS`, the sample line inheriting that ink instead of `dimmed`) and adds a non-colour cue: an `aria-hidden` `IconCheck` beside the figure and visually hidden text after the sample, "(better by 1.4 days)" / "(closer to 1.0 by 0.12)" (`betterLabel`, side-less keys `comparePeople.betterBy`/`closerTo`; the side is the column the cell sits in). The Difference column is the plain ABSOLUTE `difference` (`differenceText`: no sign, no arrow, no badge, no colour; a dash when absent); a tie, a neutral row and a side under the minimum get no tint, no icon and no hidden text — never red, no caveat text. *Drill*: every figure is a `DrillFigure` (`drillCount` decides: a share counts its
  numerator, withheld figures and the available man-days are plain text); targets `cell:<VIEW>;s=<account>` (the row's `drill`), `hist:CYCLE_*:<i>;s=<account>` (a person's bar) and
  `mix:<slice>:<key>;s=<account>` (the `DELIVERED` cell narrowed by the key as the slice); every compare list is ONE person's (the API requires `segmentAccountId`), so the drawer's person select
  has no "Everyone" (`ReportDrillDrawer allowEveryone`). `ComparePeopleDrillDrawer` composes `DistributionDrillDrawer` (bars, `segmentRequired`) and a cell/mix drawer; new `DrillUnit`s/`DrillAnchor`s
  (`changes`, `heldDays`, `ageWd`; `started`, `worklog`, `none`) serve its lists. A target the shown report did not draw is dropped like any other.
- **Person trend** (`pages/ReportPersonTrend.tsx`, `/reports/person-trend`, nav leaf `appShell.nav.reportsPersonTrend` right after Compare people, a group of one;
  `.claude/docs/reports.md` "Report 20", `.claude/docs/domain-model.md` A46). Visible to everyone (D12). ONE person's Compare-people figures over rolling 90-day windows, a point every
  14 days, across every team. *The person is the URL*: one `accountId` read WITHOUT a team (`ReportControls.peopleOnly` + `peopleCount: 1`; `useReportPage` holds the query until exactly
  `requiredPeople(controls)` people are picked), picked beside the filter bar by `components/PersonTrendPicker.tsx`, which is one **`PersonSidePicker`** (`components/PersonSidePicker.tsx`, extracted from
  Compare people and shared by both: an optional, clearable Team select — a team lists its CURRENT members, none is the unit-wide name search, changing it never clears a picked person — before the searchable person
  select; the team is remembered per viewer under the caller's `teamKey`, here `flow.viewSettings.reports.personTrend.team`, a vanished id ignored, forgotten at sign-out by `clearRememberedFilters`). The
  picker's shared strings live under `reports.personPicker.*`. *The period*: `ReportControls.defaultPeriod: "last365"` — a URL with no period is given the trailing 365 days (`presetRange`, in the configured
  zone) by `useReportFilter` and rewritten (replace), so a link means what the page shows; the filter badge counts any other period (`activeReportFilterCount`), a relative preset is remembered symbolically as
  everywhere. `normalizePersonTrendFilter` (`utils/personTrendReport.ts`) drops a team, a single member, a sprint period, other reports' keys and a second person. The bar offers the date presets and the slices
  (domain, domain view TASK, activity type, work category), no team/member/sprint (`datesOnly`); no long tail. Until a person is picked the page is the picker and an empty state and nothing is requested.
  *Layout* (`components/PersonTrendSection.tsx`): one `ReportChartCard` per `TREND_CHARTS` entry in the server's row order — the two cycle-time percentiles share ONE card (the cycle-time report's own
  `CycleTimeTrendChart`: median solid, p90 dashed), every other row has its own line (`PersonTrendChart`, one series and no legend unless a team reference is drawn, `connectNulls={false}`, a SHARE row's y axis fixed to 0..100%) — then the **Work mix** card (two `PersonTrendMixChart`s, a
  stacked `AreaChart` of the activity-type and work-category SHARES of the delivered man-days, each window summing to 100%, on the categorical palette; man-days are in its table; no cue). Titles and
  figures reuse Compare people's row labels (`reports.comparePeople.row.*`) and formatters (`rowFormat`, by unit). *Gaps and partial windows*: a window with nothing derived (`NONE`), a hidden cell and a
  cell with no denominator are `null`, so the line BREAKS (never a zero — the server's own figure for a `NONE` window is ignored, `chartPointsOf`); a `PARTIAL` window's point is a HOLLOW dot (`PartialDot`, the surface
  colour inside a thicker ring: a shape, not only a hue) and its tooltip/table say "partial" (`pointTitle`). *The cue*: under every title (and per percentile on the cycle card) the row's `trend`
  as `TrendMark` (the arrow of the value's direction, the verdict as a visible word "Better"/"Worse"/"No change" (a change within the row's `tolerance` and no verdict) or "Too few items to call" (a change beyond it and no verdict: a side is under the minimum sample), the colour teal/orange/gray — extracted from `HomeTrendMark`, which keeps Home's words), the two
  window ends and the change in the row's unit (`marginText`); the card's caption says which direction is better and that the mark compares the first and last FULL window in the range; without a cue the line says why.
  *Team reference* (since 1.21.0, `reports.md` "Report 20" "Team reference"): `chartPointsOf` carries each window's `team` and each row's `reference` onto the `TrendChartPoint` (a `NONE` window keeps its team, loses its
  reference: the peers' figure over an empty span is as meaningless as the person's). A row chart with a team median to draw (`hasReference`: SOME point has a `reference.value`) adds a second series, `referenceSeries`
  (`utils/trendReference.ts`): the team median, the quiet neutral gray (`CHART_COLORS.teamReference`, gray.6, ≥ 3:1 like every mark), DOTTED ("2 4", against the p90's "6 4" dashes), thinner, no dots, `connectNulls={false}` so a point with
  fewer than 3 peers is a GAP -- and the legend (otherwise absent: one series) names both lines. On the cycle card the dotted line is the team's median of the MEDIAN (`CycleTrendRow.reference`, legend "Team median (p50)");
  the p90's team median is only in the numbers table. No team at any window, or a team that never had 3 peers: no second series, no legend. The tooltip's second line (`referenceNote`, the shared `TrendTooltipLabel`) names the
  HOVERED point's team (a mover changes team) and peers: "Team median 4.2 (Alpha, 5 peers)" or "Team median: too few peers (2) in Alpha"; the reference is told in that line, never as a second payload row. A team change between
  consecutive points is visible in the tooltip and the table's Team column, no marker. The page's `windowsHint` gains the one-sentence rule (the dotted line is the median of the team-mates ACTIVE in the window, a gap where too few were -- the number is the server's `MIN_REFERENCE_PEERS`, kept out of the strings --, the team being the one on the window's last day) only when some row has a median to draw (`reportHasReference`). While a history horizon is set the same line adds `reports.personTrend.horizonHint` ("Windows that start before {from} ... are only partly covered: they are read from that day on" -- the server's `PARTIAL` coverage), and the shared `ReportMetaNote` above it says since when.
  *Numbers*: every card has a `DailyTableDisclosure` ("Show the numbers") with a table per window (the end, the figure — "— (below the minimum of N)" when withheld —, the sample, the coverage in words; where the person has a team at some window also a
  Team column and, per row, the team median with its peers in brackets: "4.2 (5)", "— (2)" = too few); the mix
  has one per chart (share and MD per key). *Print*: the picker is `data-print="hide"`, the print header lists Period and "Person" (`reports.print.person`), the tables print only while open. A chart with no
  figure in any window says so (an `Alert`) instead of an empty frame. *Drill* (since 1.20.0, "Drill to items" below): a CLICK on a dot (single, like the Costs slices and the cycle-time scatter dots -- not the bars' double-click) or on the hovered dot, or a click on a figure in a card's table of numbers (`DrillFigure`: the keyboard and screen-reader route; a gap, a withheld figure, a window with nothing derived and a zero share are plain text), opens `components/PersonTrendDrillDrawer.tsx` over the shared `ReportDrillDrawer` -- `GET /reports/person-trend/items?…&view=<the row's drill>&pointEnd=<window end>` (`getPersonTrendItems`, the page's own filter, so the one `accountId` and the range travel in it), headed "<row label> · <window start> – <window end>" (the whole 90 days, `windows[].from` – `end`) (`reports.comparePeople.row.*`, so the two cycle-time lines each ask their own row; the columns are Compare people's `viewColumns`). The target is `drill=point:<rowKey>:<end>` (no person segment: the filter names the person, so the drawer has no person select); a link to a row the report lacks or a day that is none of its windows is closed and dropped, never a 400. A dot calls `PersonTrendChart` / `CycleTimeTrendChart`'s `onPointClick` (the dot renderer `partialDot(color, partialAt, onClick)` and a recharts `activeDot` carrying `dotClickProps`; a gap draws no dot and the handler ignores a point with no figure); the cycle card's p90 line asks the p90 row. Each card is the focus-return anchor `trend-<first row key>` (`trendAnchorOfRow`); the work mix has no drill. The drawer compares its count with the point's `n` only for the sample views (`pointCountOf`: delivered, cycle, flow efficiency, accuracy at start, task size). Tests: `utils/personTrendReport.test.ts`, `PersonTrendChart|CycleTimeTrendChart|PersonTrendMixChart|PartialDot|TrendMark|PersonTrendPicker.test.tsx`,
  `pages/ReportPersonTrend.test.tsx`, `pages/ReportPersonTrendDrill.test.tsx` (the drawer wiring), `components/PersonTrendTables.test.tsx`; e2e `reports-person-trend.spec.ts`.
- **Costs** (`pages/ReportCosts.tsx`, `/reports/costs`, its own single-page nav leaf `appShell.nav.reportsCost` between Estimation and Flow metrics, label
  "Costs"/"Koszty" with the palette keyword "cost matrix" — a group of one like Data quality, so no `ReportTabs` and no palette-only twin;
  `.claude/docs/reports.md` "Report 16"). It was the **Cost matrix** until 1.11.0: `/reports/cost-matrix…` is `LegacyCostMatrixRedirect`
  (`components/`, a clone of `LegacyUsersRedirect`: the route's `*` tail, the query and the hash kept, case-insensitive), the REST path moved too
  (`GET /api/v1/reports/costs`, no alias), and the remembered filter key moved (`reports.cost-matrix` → `reports.costs`, a one-time reset). The author ×
  domain matrix (with its heat scale, capacity column, zero rows and Week/Sprint split) and the "by epic and author" table (with its `epic:` drill target) were
  removed in 1.11.0 (the sprint widgets came back in 1.13.0, below); `utils/heatScale.ts`, `CostMatrixTable`, `CostEpic*` and the `ReportFilterState.split` plumbing went with them (the Deep dive
  tables keep the `.heatTable` CSS classes). **Read per team, never the whole unit** (`CONTROLS.teamRequired`, like Data quality: the web opens on the TEAM level;
  a `domain` with no team is the API's UNIT-level answer, which the page never asks for; its `levelTitle.UNIT` string stays for it). The page: tiles (man-days, foreign man-days, the overall foreign share — a dash and
  the reason when the server sends `null`, never 0%) in one `ReportChartCard` titled by the level (`levelTitle`: "Man-days logged by the team" / "… by this author", the
  member filter narrowing it) whose caption says whose domain a `domain` filter follows (`domainView.EPIC|TASK`, the bar's "Delivered in / Earned in" toggle, default EPIC), then the
  hint lines (what foreign work is, the sprint note under a sprint-relative period — `isSprintRelative`, `meta.from === null`, "this team's own sprints" —, the rounding rule),
  then the two breakdown cards. The card is empty ("No data in this period") when both breakdown lists are empty. Every figure is the server's own rounded exact sum, NEVER re-added
  from the slices shown. The bar offers period, team/member, domain, domain view, activity type and work category (no connection control: the API still accepts
  `connectionId`, the page does not); `normalizeCostsFilter` (`utils/costsReport.ts`) drops `bucket`, `by`, `itemKind`, `epicId` and a stale `connectionId` off the
  request and the URL, and a retired `split=…` of an old link is no managed key any more (it is ignored like any unknown param).
  **Activity-type and work-category donuts.** Under the tiles, `components/CostBreakdownSection.tsx` draws the response's `byActivityType` and `byWorkCategory`
  (`.claude/docs/reports.md` "Report 16", Breakdowns) as two cards — "Man-days by activity type" / "… by work category" — side by side from the `lg` breakpoint, each a donut
  (`components/CostBreakdownChart.tsx`, a lazy chunk importing `@mantine/charts/styles.css`; Mantine's `DonutChart`, one mark set, no axis, no built-in legend, a custom
  hover card, found by the entry's `id` rather than its name, with the figure, share and, for "Other (N)", the folded keys; segment names are made unique because Mantine keys a segment by name) over its legend TABLE (name with a swatch, Man-days (MD), Share — every number of the chart, in a
  `ScrollRegion`). **A breakdown whose own filter is set is not drawn** (one slice): the activity-type card only while the Activity type filter is unset, the work-category card
  only while Work category is unset (`visibleBreakdowns`, `utils/costBreakdown.ts`); both set, or all-zero lists, draw none. **Folding** (`foldBreakdown`, pure,
  tested): zero-MD slices are dropped, every slice under 3% (`OTHER_SHARE`) of the list's own total folds into ONE "Other" slice — only when that folds at least two — placed
  last and named "Other (N)" (N = the folded keys, so it cannot collide with a real key "Other"), its figure the folded slices' `exactMd` added and rounded once —
  the very number the drill's bar reports (two slices of 0.125 show 0.13 each, Other 0.25) —, listed in its legend row ("Includes: Planning, Ops") and its hover card; shares are of the sum of the drawn slices,
  so they add up to 100%, and the rounding rule's footnote applies. **Colour:** named slices wear `CATEGORY_PALETTE` by position WITHOUT its gray (`sliceColors`; six hues, then
  they repeat — the ring's last named slice skips a repeat of the first one's hue when no Other follows it) and gray.6 is "Other"'s alone; identity never rests on colour — the legend row names every slice. `UNCATEGORIZED` reads "(uncategorized)" (`reports.filters.uncategorized`).
  **Drill:** a click on a slice, or the legend row's MD figure — a link-styled `Anchor` button whose accessible name STARTS with the visible figure ("8.5 MD — show the tasks of
  Development"; the keyboard and screen-reader route, a plain figure while the shown data is kept data of a previous filter) — opens `CostBreakdownDrillDrawer` (the shared
  `ReportDrillDrawer`) with the slice's tasks from `GET /reports/costs/items` (`getCostsItems`, `api/reportDrill.ts`: the page's filter, `by`, one repeated
  `key` per folded type). Target `cost:<BY>:<key>,<key>…` in the page-local `drill` param (`utils/reportDrill.ts`, keys percent-encoded; ≤ 100, a malformed spelling is ignored);
  the drawer title is the slice over the breakdown as its dimmed subtitle ("Development" over "Activity type" / "(uncategorized)" over "Work category" / "Other (N)"; with an "Includes: …" caption), its columns Issue · Summary · Kind (always:
  an epic logged on directly is listed as an epic) · Authors · Last logged · Logged (MD) (`DrillUnit` `loggedMd`, `DrillAnchor` `logged`; "No known author" when no worklog names one).
  **Sprint widgets (1.13.0; `.claude/docs/reports.md` "Report 16", A44).** Under the donuts `components/CostsSprintSections.tsx` draws the response's `sprints[]` and
  `outsideSprintsMd` for a real team (the page always reads one): (1) **"Actual cost per sprint, by epic"** (`CostsSprintEpicSection`) -- the SHARED
  `SprintEpicSection` of Commitments/Throughput, generalised with a `toTarget` prop (what a segment becomes: Costs' `costsEpicTarget`, a `cost-sprint` target, instead of a sprint
  bucket) and a `note` prop; `EpicStack.bucket` and `EpicFigure.bucket` are optional for it (an actual-cost bar has no sprint bucket; `epicSegmentTarget` returns `null` without
  one). One stack (`COSTS_STACKS`, id `a`), the segments in the SERVER's order (`costsEpicChart` over `sprintEpics.ts`' generic `epicChart`), `EPIC_PALETTE` / `NO_EPIC_COLOR` /
  `OTHER_EPICS_COLOR`, the table Sprint · Epic · Actual cost (MD), left out when no sprint has a segment; (2) **"Actual cost against capacity, per sprint"** (`CostsCapacitySection`,
  lazy `CostsCapacityChart`) -- a gray `actualCost` bar per sprint over Commitments' capacity line idiom (dashed gray, one dot per sprint, no marker for a sprint without capacity, the
  series absent when none has one; the page's own `ChartLegendList` legend -- a gray swatch against `DashedLineGlyph` -- since both series are gray.6 and Mantine's legend would print two identical swatches; the tooltip names the source with `reports.commitments.capacitySource.*`) and the table Sprint · Actual cost (MD) · Capacity (MD) · Actual ÷
  capacity · Capacity source (`reports.costs.sprints.source.*`; a null capacity reads "—", a capacity of 0 reads 0 and only the ratio is "—" then). **The capacity is everyone's**: there is NO role check anywhere in the widget
  (A44 amends A39); at USER level the server sends none and a note says why. (3) One dimmed line, "N MD logged in the period outside the listed sprints", when `outsideSprintsMd` > 0
  and sprints are listed. With no sprint listed a single card says why instead (`sprints.needTeam` for the UNIT level, `sprints.none` for a team that completed none).
  **Sprint drills:** target `cost-sprint:<teamId>:<connectionId>:<sprintId>` (the sprint's WHOLE bar, from the capacity chart's double-click or its table's figure) or
  `cost-sprint:<teamId>:<connectionId>:<sprintId>:e=<epicSegment>[,<topEpicId>…]` (one epic segment, spelled like a by-epic sprint bar; "Other epics" carries the chart's top epics; never one
  person's, `;s=` spoils it). `getCostsItems` (`api/reportDrill.ts`) sends the page's filter with the period narrowed to the sprint (`sprintDrillFilter`: `sprintId` + the bar's
  `teamId`, no `from`/`to`/`lastSprints`) and the target's `connectionId` (Costs draws one bar per (connection, sprint) and a sprint id can exist on two connections, so the connection is part of the chart row id -- `sprintRowId`, `<teamId>:<connectionId>:<sprintId>`, a Costs-only shape -- and of the codec), and NO `by` for the whole bar, `by=EPIC` + `epicSegment`
  (+ repeated `topEpicId`) for a segment. The ONE `CostBreakdownDrillDrawer` serves all three shapes: a sprint bar is "Actual cost" / "Actual cost — <epic>" over the sprint's name, and
  its summary is held against the chart's `acMd` (`chartMd`, `costsChartMd`) so a refreshed dataset is announced. Focus returns to the capacity card (`costs-capacity`) or the by-epic card
  (`costs-epics`) (`costDrillAnchor`). Pure model: `utils/costsSprints.ts`. Tests: `pages/ReportCostsSprints.test.tsx` (chart probe mock incl. `CompositeChart`; fixtures
  `test/costsSprintsFixtures.ts`), `utils/costsSprints.test.ts`, the `cost-sprint` cases of `utils/reportDrill.test.ts`, `utils/sprintEpics.test.ts`.
  **Work-category widgets (1.14.0; `.claude/docs/reports.md` "Report 16", A45).** Between the donuts and the sprint widgets `components/CostsCategorySections.tsx` draws the response's
  `categoryTargets` and `categoryShareTrend` (both cut the very worklogs the work-category donut splits; neither is drillable, so neither has a focus anchor). (1) **"Work-category targets
  against the actual mix"** (`CostsTargetsSection`, lazy `CostsTargetsChart`) -- only while `categoryTargets` is present, i.e. the team's WHOLE mix (the server sends `null` at USER level and
  whenever a domain / activity type / work category slice narrows the worklogs; one dimmed line says which, `targets.userNote` / `targets.sliceNote`, and nothing at UNIT level). Not `configured`:
  a short note ("<team> has no work-category targets yet.") and a link to the team's targets page (`teamWorkCategoryTargetsPath`, the Team work-category targets section above) -- shown to EVERYONE,
  since only editing is the administrators'; no chart, no table. Configured: a **diverging horizontal bar** of `deltaPoints` per category (Mantine `BarChart` `orientation="vertical"`, ONE series
  in ONE neutral hue -- `CHART_COLORS.categoryDelta`, the cost gray; red means blocking in this app and the sign is no verdict, so the side of a centred zero reference line and the signed
  figure carry it --, a symmetric value axis `±deltaAxisMax` (steps of 5 points, at least ±5), no legend for one series, a row with a null delta is not plotted) over the table Category · Target % ·
  Actual MD · Actual % · Δ (pp) in the server's row order (`UNCATEGORIZED` reads "(uncategorized)" like the donut legend, the drill drawer and the filter, through `keyLabel`; a null `actualShare`/`deltaPoints` reads "—"; the delta is `formatDeltaPoints`: a true minus, ONE
  decimal, `0.0` unsigned); a row with `mapped: false` wears the orange "Not mapped any more" badge. The targets are the team's CURRENT ones, also for an earlier period (the note says so). (2)
  **"Work-category share, cumulative by week"** (`CostsCategoryTrendSection`, lazy `CostsCategoryTrendChart`) -- a Mantine `LineChart`, a line per category (every key of the first week; the server
  repeats the key set in every week) of its cumulative share, 0-100 % on ONE axis, legend from two lines, `connectNulls` off (a week with nothing logged yet has a null share: a gap, a dash in the table),
  series named positionally (`c0`...: a category label may contain a dot, which recharts reads as a path) and a hue the palette already used is dashed; the table (Week of (Monday) · Logged so far
  (MD) · a column per category, newest week first, sticky header in a `ScrollRegion` capped at 320 px) carries every number. `null` from the server is NOT a card: at TEAM/USER level it means a period
  beyond 160 weeks ("Pick a shorter period."), at UNIT level it needs a team -- one dimmed line (`costs-trend-unavailable`). **Colours** (`utils/costsCategories.ts` `categoryColors`): the donut's own
  (`foldBreakdown` + `sliceColors` over `byWorkCategory`, so Run is the donut's first hue in the table's swatch, the line and the slice); a category the donut folds into "Other" or only the targets
  know continues down `SLICE_PALETTE` (no gray: "Other"'s gray would name many lines), repeating from the seventh -- identity never rests on colour (legend, tooltip, both tables). Tests:
  `pages/ReportCostsCategories.test.tsx` (the placement and outline, the unconfigured link, the USER / slice / beyond-160-weeks lines), `components/CostsTargetsSection.test.tsx`,
  `components/CostsCategoryTrendSection.test.tsx`, `utils/costsCategories.test.ts`; fixtures `test/costsCategoriesFixtures.ts` (the other Costs fixtures send both blocks as `null`).
  **ONE `useReportDrill` per page:** `pages/ReportCosts.tsx` owns it (`costDrillAnchor`: the clicked card) and hands `drill` to the sections -- two hook instances would both return focus on close.
  The drawer lives in `CostBreakdownSection`, so a deep link (`drill=cost%3AWORK_CATEGORY%3ARun`, `drill=cost-sprint%3A1%3A12`) opens once the report is in hand. Tests: `pages/ReportCosts.test.tsx` (the tiles, the domain view,
  the pasted-link normalization, the sprint note, the required team), `pages/ReportCostsBreakdown.test.tsx` (the tables, the filter hiding, folding, the legend figure's name and request,
  Other's repeated keys, a slice click, the deep link), `components/CostBreakdownChart.test.tsx`, `components/LegacyCostMatrixRedirect.test.tsx`, `utils/costBreakdown.test.ts`,
  `utils/costsReport.test.ts`; the page tests stub `@mantine/charts` (the breakdown tests replace the stub with a probe that fires the pie's `onClick`).
- **Deep dive** (`pages/ReportDeepDive.tsx`, `/reports/deep-dive`, its own single-page nav leaf `appShell.nav.reportsDeepDive` — a group of one like
  Costs; `.claude/docs/reports.md` "Report 17", A29). **The URL is the selection and nothing else** (`hooks/useDeepDiveUrlState.ts` reads and writes it — the selection, its canonical key, the view, `applySelection` = a history entry, `setView` = `replace` — over `utils/deepDiveFilter.ts`: repeated
  `sprintId`/`epicId`, `domain`, `connectionId`, `from`/`to`; forgiving parse that keeps ONE mode by precedence, canonical order, sorted
  values — that string is the page query key; `issueId` is a RETIRED key — the removed "Tasks of an epic" mode's — never parsed, deleted on the next write and
  reported as `retired` by the hook, so a gray note under the panel says the link's tasks view no longer exists and the whole epic is shown). **The last SHOWN selection is remembered**
  (`reports.deepDive.filter`, `utils/deepDiveRemembered.ts`; "Remembered filters" under Reports has the rule): written by `applySelection` (Show) and `setView`
  only, as the canonical selection query plus `view=burnup`. A bare visit — no selection key at all, the retired `issueId` counting as one so an old link is
  taken as written — restores it once the reference data is in: the parser a pasted link goes through (so `issueId`, a conflicting mode or a malformed value
  never survives), the domain and the connection checked against `/reports/filters` (a gone domain takes its sprints with it), sprint and epic ids having no
  client-side list; the URL is rewritten by `replace`, flagged in `location.state` (`deepDiveRestored`, the selection key). A restored selection the report
  answers with a `400` for (a deleted epic) is **forgotten** — the memory emptied, the URL back to the bare page, the explainer instead of the error;
  one the user showed or a link carried keeps its message. Layer switches (`Switch`es for plan, approximate EV, cost) are remembered per viewer
  (`useStoredState`, `reports.deepDive.layers`, all on by default; a corrupt value — or one stored before Approximate EV, whose key was `exec` — fails `isLayers` and falls back to all on, once), drill state is local to the matrix. **No selection**
  (or an incomplete one in a pasted link) shows an explainer of the two ways in and requests nothing; the report is requested only for a complete
  selection, with NO kept previous data (a new selection is a loader, never a stale grid) and `DeepDiveMatrix` is keyed by the serialized selection
  so a cached answer for another selection never inherits an open month. Failures are inline: a `400` prints the server's own `detail` (too many
  tasks, an unknown epic — admin-grade prose, shown as written) under "This selection cannot be shown", anything else the shared `ErrorAlert`.
  **`components/DeepDiveSelectionPanel.tsx`** (draft logic in `utils/deepDivePanel.ts`): a `SegmentedControl` for the two modes — Sprints of a domain
  (max 52 sprints) and Epics (max 50). **Layout and fold**: a bordered card with the h3 "Selection"; the mode switch sits alone in the first row, then ONE wrapping row — Domain, the mode's picker, From, To, Show (primary, last; an `Input.Wrapper` with an invisible label gives it the inputs' top edge) — with every hint (`N of 52 selected`, the cut-list note, "pick a domain first") UNDER its control (`inputWrapperOrder`), so no description pushes an input off the row's line; the removed-picks `role="status"` line and Show's blocking reason are the two helper lines below the row. **Over a shown selection (the URL's, `isShown` = complete) the card folds** to a one-line summary (`selectionSummary`: domain name · `Sprints: <names>` / `Epics: <keys>` · `from → to`, at most 3 names then `+N more`, a missing range bound `…`) and a default-variant **Edit selection** disclosure button (ONE constant name — visible text = accessible name — with a chevron, `aria-expanded` and `aria-controls` the form's wrapper carrying the state). Show folds it and hands focus to the toggle (its button unmounted), the toggle opens and folds; a `400` for the shown selection (not the restored-and-forgotten path) opens the form for that visit without touching the stored choice (`refused` prop); the last choice is remembered per viewer (`useStoredState`, `reports.deepDive.selectionFolded`, folded by default; the draft survives a fold — it keeps what the option lists said about each pick (`PickFacts.label`/`name`), so a searched pick keeps its pill text after a fold and the summary names a just-shown sprint before any report arrives; a loaded report's names override them — and the pickers are unmounted while folded so they request nothing). A bare page or an incomplete link has nothing to summarize: the form stays open and there is no toggle. **The Domain `Select` (the shared filters' domains) sits above the picker in BOTH modes**: the picker is
  GATED (hint "Pick a domain first", no option request) until a domain is chosen in BOTH modes — the sprint picker is `disabled`; the EPIC picker stays a live control
  with no dropdown and no search (`DeepDivePicker`'s `removableWhenDisabled`; a `disabled` Mantine MultiSelect hides the clear and pill-remove buttons, which would lock a link's epics in), so its picks can be removed or cleared but not added to; in Sprints the domain is part of the selection, in Epics it is a pure
  option-list filter (clearable, never "any") — passed to `GET /deep-dive/epics` only (the server keeps `domain` optional), the selection and the URL stay the
  epics' (the report would `400` `domain` with `epicId`). A link's epics without a domain are still a valid selection (the report loads, the picks show by the
  report's names, Show is ready); only ADDING epics needs a domain. **There is no Connection select**: every option carries its `connectionId`, a list whose
  answer spans more than one connection names it in each label (` · <connection>`; a value present in two connections is then two options — the picker's
  internal option token is `value@connectionId`), and the selection's `connectionId` is derived from the picks (`pickConnections`) and written ONLY on a stack
  with more than one active connection and only when the picks name exactly one — so a single-connection URL stays `?domain=FLO&sprintId=N`. A link's
  `connectionId` rides on its picks (showing the link's selection again writes the same URL; a stack with one connection drops it); an option answer
  fills in the connection and the domain a link's pick lacked (`learnPicks`); picks from two connections block Show with "Pick work from one connection
  only — the options name their connection." **Changing the domain**: in Epics it keeps EVERY epic picked (a selection may span domains; `withDomain` only sets the domain, the pill text of a pick from another domain comes from
  `PickFacts.label`, learned when it was picked, else the loaded report's names) and only narrows the picker's list; in Sprints the sprints stay and the next UNFILTERED sprint list of the
  new domain settles them (`pruneSprints`: when that list is complete — total fits the page — a sprint it does not list is dropped, a cut list leaves the
  picks alone) and a `role="status"` line says "N sprints removed: not in <domain>", cleared by the next pick, domain or mode change.
  Switching the mode drops the picks (they belong to the old mode) and keeps the domain and the dates. Sprint options read `name · from → to` through
  `utils/sprintLabel.ts` (`endAt ?? completeAt`, the last day = the final millisecond's; also Capacity's sprint picker and the report filter bar's Sprint select;
  `ReportFilterSprint` carries `endAt` for it). The label shows the sprint's PLANNED end, as Capacity does, which can differ from the report's `range` (the envelope uses `complete_at ?? end_at`) for a sprint closed early or late.
  The pickers are `components/DeepDivePicker.tsx`: a searchable `MultiSelect` over a server list, the typed text debounced 300 ms into `q`,
  the combobox's own filter switched off (the server already matched key AND summary, each on its own), picked values kept in the data (labelled by
  any option answer the picker has seen, else by the loaded report's names, else the value) so they survive any search, a "N of MAX selected" line,
  a "showing the first N of M" line when the page (100) was cut, a hint while a picker waits for a domain, the previous answer kept on screen
  while the next loads (same scope only), the spinner on the LEFT so the clear button stays, and the picker's own failure inline. The panel holds a DRAFT and only the explicit
  **Show** button writes it to the URL (a push, so Back works; the selection already shown pushes nothing, unless it cleans a retired key off the address) — typing and picking fire no report
  request, and Show does NOT rebuild the panel: the page resets it (a new `key`) only when the URL changes from outside (Back/forward, a followed or pasted
  link), never for the change its own Show made. A blocked Show stays focusable (`aria-disabled`, activation ignored) and its `aria-describedby`
  live line says what is missing for the mode ("Pick a domain and at least one sprint", …); `From`/`To` are optional `YYYY-MM-DD` text inputs (clip) whose malformed or
  reversed/over-1100-day values name the problem and block Show. A `NOT_DERIVED` answer is one gray note and no grid; `RANGE_CLAMPED` an orange note
  above the grid, and so is a `HISTORY_HORIZON` note (`noteOf` matches the code with `includes`: a lone one is kind `HISTORY_HORIZON`, one joined after a clamp with ". " keeps kind `RANGE_CLAMPED` and sets `historyHorizon`, so both messages print, each its own alert; the horizon's message is translated from `meta.historyHorizon` -- its first day and months -- never parsed from the note's text, which is the fallback only when the meta carries none) — all translated, the server's English `note` is shown verbatim only for any other kind; under the matrix sit the **data limits**
  (`components/DeepDiveLimits.tsx`: the eight `quality` counters, zeros included — among them the open tasks in progress and those done without an estimate (A40) —, each with a one-line why, then the fixed rules — as-was domain,
  sub-tasks roll up, the Approximate EV rule (a linear-progress simplification over the in-progress working days; waiting and blocked days earn nothing; a flagged day in an in-progress status counts, the flag is not subtracted), a worklog on its start day — and the freshness day `range.asOfDay`).
  **The matrix** (`components/DeepDiveMatrix.tsx`; the model is split by concern — `utils/deepDiveCalendar.ts` the month → ISO week → day column partition
  (`TimeColumn`, `grainColumns`, `expandedColumns`), `utils/deepDiveAggregate.ts` the cell/row shapes and the per-column sums, `utils/deepDiveNote.ts`
  the server note's kind (`noteOf`, `isDerived`), `utils/deepDiveMatrix.ts` the epic grouping, planned windows and `buildDeepDiveMatrix`; each with its
  co-located test): a `<table role="grid">` in a `ScrollRegion` with a
  roving tab stop (the generic `hooks/useRovingGrid.ts` — positions only, arrows, Home/End, Ctrl for first/last row or column; the matrix feeds it its
  bounds and marks cells with `rovingProps`), drill headers that are buttons with `aria-expanded` (months → ISO weeks → days, epics → tasks), a polite live
  region for each drill, ONE tooltip on hover or focus (Escape closes it), every cell's `aria-label` carrying its numbers, and the legend, a summary
  table and the visible figures behind `DailyTableDisclosure` as the text alternative. Contrast and identity: plan (`flow.6`) is a full-width bar,
  Approximate EV (`teal.8`, A40) two-thirds, cost (`gray.6`) one-third, each semi-transparent with a solid 2 px edge that carries the 3:1, so colour is never the
  only cue; **all three share ONE man-day scale** (`LayerScale.mdMax` = the largest of the PV, Approximate EV and AC cells in view — the legend says so). A
  task worked on that earned nothing (an open task, or one done without an estimate; the model's `MatrixCell.unearned`, `evState` not EARNED, counted per
  in-progress day) is a **hollow mark**: `data-layer="unearned"`, two-thirds wide, a fixed 6 px, at the cell's TOP (the bars grow from the bottom), a dashed `teal.8` edge with no fill (the same 3:1 edge and surface
  halo as the bars, no new hue), drawn with the Approximate EV layer and gone with its switch like the ◆; the tooltip and the cell's `aria-label` name it
  ("In progress, nothing earned: FLO-7 (not done yet, 3 days)" in the label; a headed section of label/value rows in the tooltip). **The tooltip is
  sectioned** (`DeepDiveCellTooltip`, the sections built by `cellSections` for the hovered cell only — `cellFacts`, which runs for every visible cell's label, builds none): the cell header (title, then the dates), then one
  divider-separated block per layer shown — Plan (PV), Approximate EV, Earned value (EV) with a "Done" row, In progress / nothing earned (a row per
  task), Cost (AC) with a row per author — each a small uppercase heading with its figure on the right, detail rows label-left/value-right in tabular
  numerals; a block with nothing to list adds no rows, a layer that is off no block, and an empty cell only "nothing in this period". The label's sentence
  (`factsSummary`) is unchanged; labels and the date line use the theme's AA-pinned `dimmed` ink (`ddTipMuted`), while every figure, author costs included, stays in full ink. Approximate EV's whole-life sum IS the task's EV, so the summary table has an in-range column for
  it and no whole-life one. **The 25k guard:** more visible cells (rows ×
  columns) than 25,000 — or more figures in the bucket table than that — replaces the grid with a notice asking to collapse or narrow, so the DOM stays
  bounded however large the answer.
  **The Burn-up tab** (`components/DeepDiveBurnup.tsx`, its lazy chart `DeepDiveBurnupChart.tsx`, the sums in `utils/deepDiveBurnup.ts`): Mantine `Tabs`
  "Matrix" | "Burn-up" under the selection, ONE fetch for both (the burn-up is client-side over the same answer). The tab lives in the URL as
  `view=burnup` (`parseDeepDiveView`/`applyDeepDiveView` in `utils/deepDiveFilter.ts`; the matrix, the default, is the param's absence; `view` is a
  foreign param to the selection functions, so Show and the page query key keep it; a tab hop is a `replace`); the matrix panel stays mounted (its
  drill state survives a hop), the burn-up renders only while open, and the layer switches live in the matrix panel only. The chart is a `LineChart` of
  CUMULATIVE PV (`flow.6`), EV (`teal.8`), Approximate EV (the same `teal.8`, dashed `4 3` — no new hue, the budget line's dash precedent; the EV spread over the in-progress days, so it rises while EV steps), AC (`gray.6`) per day, one man-day axis, each starting at 0 on `range.from` and counting only what falls in
  the shown range (a note says so; whole-life totals stay in the matrix summary), so a line's end equals the matrix model's `inRange` total. AC is the
  tasks' cost plus the epics' own worklogs (EPICS mode; a note says so). EV, Approximate EV and AC are actuals and end on `range.asOfDay` (`null` after it, `connectNulls`
  off; a later entry extends them) while PV runs on, and the as-of day is a labelled dashed `referenceLines` marker. The optional dashed plan-blue **epic
  budget plan** (a `Switch`, off by default and remembered per viewer — page state under `reports.deepDive.burnupBudget`, so it survives a tab hop — offered only when some epic
  has a planned window and a budget) spreads each epic's budget over the working days of its window with report 15's running `ROUND(total * i / n, 2)`
  rule; a window reaching outside the range keeps its full length and the line shows the part inside the range — for the days outside the shown range it
  assumes a Monday–Friday week and no holidays (the configured weekend and holidays are known only inside it); it covers whole epics, however many
  tasks are picked, and is a separate comparison line, never part of a PV total. The as-of label is text, so it takes `AS_OF_COLOR` (`gray.7` light,
  `gray.4` dark, ≥ 4.5:1 on each scheme's surfaces) and hangs off its line toward the side with room (`insideBottomRight` in the range's right half). The daily table (newest first, a dash where an actual has ended) sits behind `DailyTableDisclosure`; week/month aggregation is not
  offered, the x axis thins its ticks instead.
- **Home overview** (`pages/Home.tsx`; `utils/homeOverview.ts` is its pure logic, `utils/homeTeams.ts` the team-pick rules, `api/home.ts` its wrapper). The landing page is the overview of
  UP TO THREE teams at a glance -- never the remembered report team -- over the trailing 90 days, each figure beside the 90 days before.
  **Which teams.** `components/HomeTeamPicker.tsx` (the page header's `toolbar`, `ReportPageHeader` passes it through): one `MultiSelect` (label "Teams shown", `maxValues` 3, `hidePickedOptions`, searchable, clearable -- clearing goes back to the default; the description says "Up to 3 teams" at the cap, else explains the default while no pick is remembered) plus a subtle "Back to my default teams" button shown only while a pick is remembered. The pick is remembered per browser under `flow.viewSettings.home.teams` (JSON number[], `null`/absent = the default; `clearRememberedFilters` removes it at sign-out) and validated against the reference data before every request (`livePick`: stale ids dropped, nothing left = the default; a restore never writes). **The default rule lives in the web** (`defaultHomeTeams`: the teams whose `managerUserId` -- from `GET /reports/filters` -- is the signed-in user first, then the rest in filters' name order, at most three), because the server's default must not depend on who asks (D12, `reports.md` "Home overview"); the page always sends explicit `teamId`s. Load order: filters (`["reports","filters"]`), then the overview (`["home", ids]`, `keepPreviousData`: a new pick keeps the old overview on screen with `aria-busy` on the sections group until the new one arrives).
  Its header is `ReportPageHeader` (print / save as PDF; the printed summary names both windows and the teams). **ONE overview request answers it**: `GET /reports/home?teamId=…` (staleTime 60 s; server: `reports.md` "Home overview"), pinned to the filters request plus exactly one overview request by
  `Home.test.tsx` (the pick behaviour: `HomeTeamPick.test.tsx`); the page renders sections only once it has loaded, so a section has no pending or error state of its own (a placeholder `group`
  `aria-busy` while loading, ONE `ErrorAlert` on a first-load failure -- a failed BACKGROUND refetch keeps the overview and shows the alert above it -- the empty state when `meta.derivedAt` is null -- the admin links stay -- and a one-line note
  when no team exists). The page is: a **freshness line** (`HomeFreshness`: each active connection's last sync and derive read in the response's own
  `timeZone` via `formatDateTime`, and the two windows, plus the shared "Reports show work since {from} (history horizon: {months} months)" line from `meta.historyHorizon` while a horizon is set -- Home's windows are never cut by it) then seven `HomeTile` sections (a card whose h3 title IS the link into the full report, a caption
  naming the window and what a trend arrow compares with, `data-drill-anchor`), in this order: **Last sprint** (`HomeSprintSection`: capacity gray,
  initial/final/delivered of each team's last closed sprint -- the Commitments colours -- the "Drift" badge with the frozen figures in its table, a note naming
  the teams with no closed sprint; links Commitments `lastSprints=1`, Throughput, Sprint consistency), **How big the work is** (`HomeGranularitySection`:
  median estimate, cycle time in working days and logged cost -- LOWER is better), **How well estimates hold** (`HomeAccuracySection`: one PRED curve per
  team -- tasks solid blue, epics dashed gray, x = ±10/25/50 %, a gap where the sample is under the minimum -- with a ±25 % summary line carrying the trend (arrow plus the verdict as a VISIBLE word: "Better", "Worse", "No change", the last with a sideways arrow);
  the team name links to the task accuracy report for that team and window), **Epics in progress** and **Tasks in progress** (`HomeWipSection`: the count now and
  the p50/p85/p95 age in working days as FOUR charts, never three shades of one hue; no trend, a snapshot), **How much is delivered** (`HomeThroughputSection`:
  per team the weekly man-days in teal (Monday-start weeks, the first and last possibly partial, which the caption says) with the window's totals beside the previous window's and the trend, then two small multiples for the cycle time p50 and
  p85) and **What is missing from the data** (`HomeQualitySection`: NOT charts -- ONE visible table of counts, "Findings per team", in a `ScrollRegion`: a row per team
  in the overview's order, a column per finding under the two group headers "Tasks done in the window" (three, each with the trend marker `HomeTrendMark`, arrow plus
  the verdict as a word) and "Epics" (three, the Data quality report's own population, no trend), the gap column naming the configured tolerance. It IS the accessible
  table, so the section has no hidden twin. A count above zero is a `DrillFigure` button ("3 -- Tasks with no estimate: show the items of Alpha", the same
  `home:<VIEW>:<teamId>` drill the bars opened, focus returning to the button) in the soft-finding ink (`--flow-ink-warning`, `.findingCount`); a zero is dimmed plain
  text, not a button. A dimmed note under it counts the work of no team ("Not in any team": the server counts every active team, not only the picked ones), with a link to Data quality). The window length in the copy ("Previous N days") comes from the response's own `window` (`windowDays`, provided through `HomeWindowDays`), and counts are pluralised in both languages (`home.count.*`, tooltips say "tasks" or "epics" by view). Every link carries a period (`overviewLink`: the response's `window`, or `lastSprints=1` for the sprint section; a bare report URL would apply
  the remembered filters), the aging link adds `kind=epics` for the epics section; in sections 3 and 6 each team's chart title is its name, linking with
  `teamId` + window.
  **Cost.** Thirty-odd charts (sections 1-6; section 7 is a table): each is mounted only when near the viewport (`NearViewport`, an IntersectionObserver with a 400 px margin; the chart's TABLE is never deferred; without an observer everything mounts at once, which is also what the test setup gives), the sections are memoised on the overview with a stable `open` so a drill's URL change redraws none of them, and the reference data Jira links need is requested only when the first list opens. **Small multiples** (`HomeTeamBars` + the lazy `HomeTeamBarsChart`): one question per chart, horizontal single-series bars in the team order every chart
  shares (team names stay legible), a team under the minimum sample draws no bar and says so in its tooltip; colours from the vocabulary only (granularity,
  WIP and cycle `flow.6`, deliveries `teal.8`; the findings are a table, not bars). **The trend marker**: an arrow at the end of the bar
  (`HomeTrendLabel`, an SVG `<text>`, `aria-hidden`) -- or in the flow of a line (`HomeTrendMark`, with a visually hidden verdict word) -- whose GLYPH is the
  direction of the value (↑ ↓ →) and whose colour is the SERVER's verdict (teal better, orange worse, gray none/tie); the verdict is also TEXT in the tooltip
  ("Alpha · 12 items · Better", the previous value as a second row) and in the chart's table, so colour is never the only carrier. `trendOf` reads
  `previous.verdict`/`difference`; nothing is judged client-side.
  **The keyboard and screen-reader route (no visible tables)**: every chart has its own table (`HomeHiddenTable`: a `ColumnTable` named "<chart title>, as a
  table" in a wrapper with the `homeHiddenTable` class of `theme.module.css`) that is clipped to 1 px -- never `display: none`, which would take its buttons out
  of the tab order -- and **revealed in place on `:focus-within`** (the skip-link idiom). Screen readers always read it; a keyboard user Tabs into the
  chart's first figure, which reveals the table; a mouse user never sees it and double-clicks the bars. **One Tab stop per table** (`hooks/useRovingButtons.ts`, the DOM-position sibling of `useRovingGrid`, which would need every cell renderer to carry a position): Tab lands on the table's last-focused figure (else the first), arrows move along a row / down a column, Home/End (Ctrl: the table's) jump; on focus the revealed box scrolls the button into view and has padding so the focus ring is not clipped. In the weekly table only the Items figure is a button. Its columns (`utils/homeColumns.tsx`'s `figureColumns`):
  Team (or Measure) · the figure (a hidden one reads "Below the minimum sample") · **Items**, the drill button (`drillFigureColumn` around `DrillFigure`; a
  count's own value is the button) · Previous 90 days · the verdict as text; a PRED table adds the sample. Tests cannot see the CSS (happy-dom), so the reveal
  is pinned by the e2e suite; the unit tests pin the table's content and that its buttons are in the tab order.
  **The drill** (`HomeDrillDrawer`, mounted once by the page; `useReportDrill("home", drillAnchorOf)` with `data-drill-anchor` on every section card): every
  bar and every Items figure opens the shared pane. THREE target kinds share the `drill` param: `sprint:<teamId>:<sprintId>:<BUCKET>` (the existing
  `SprintDrillDrawer` over `sprint-consistency/items`; the capacity bar is a figure, not a set of items, and has no drill), `week:<teamId>:<monday>` (a weekly
  bar -> `ReportDrillDrawer` over the Throughput items for that team and the overview's window, `bucket=WEEK`) and `home:<VIEW>:<teamId>` (one of the 17
  `HomeDrillView`s -> `GET /reports/home/items`; `HOME_VIEWS` gives each its figure unit and date column and the overview's own count the list is compared
  with -- a PRED compares with the items WITHIN the share). A target the overview did not draw (a team it no longer lists, a sprint that is no longer a team's
  last, a week outside the window, a kind Home has no bar for) is dropped from the URL once the overview is in hand (`drillDrawn`), never a drawer that answers a
  400; a deep link opens after the load. A PRED point double-clicks through the line's `dot`/`activeDot` objects (`lineProps(series)` closes over the series, and `dotDoubleClickProps` takes whichever handler argument carries the data row, so a recharts change in argument order cannot silently break it); a point with nothing within its share opens nothing; the table's buttons are the keyboard route there too.
- **Load order**: `["reports","filters"]` (staleTime 60 s) → the page query keyed
  `["reports", <report>, <serialized filter>]`, `enabled` once the filters loaded,
  `placeholderData: keepPreviousData` (`ReportChartCard` dims the previous body and sets `aria-busy`).
  `ReportChartCard` owns the load/empty/error triage; the row NAME in `ReportGroupsTable` is a
  `RouterLink` narrowing `teamId` (UNIT) or `accountId` (TEAM) — "name is the way in".
- **Chart rules** (ported from Lettuce): charts live only in lazy chunks (the page lazy-imports its
  chart component), each chart component imports `@mantine/charts/styles.css` itself, a legend for
  two or more series (none for one), one axis (never dual), and every chart has its numbers in a
  table beside it (commitments and the sprint charts: the per-sprint table; throughput's period chart:
  `ThroughputBucketTable`; Home's small multiples keep theirs visually hidden until a figure takes keyboard focus -- see Home overview). Colour is never the only carrier of identity — legend, tooltip and table are. Tests mock `@mantine/charts` (recharts draws nothing under happy-dom) and assert
  the props. **Colour vocabulary, no new hue** — concrete shades in `utils/chartColors.ts`: blue `flow.6` =
  plan/committed, teal `teal.8` = delivered, orange `orange.8` = carried over/added scope, red
  `red.7` = dropped/blocked, gray `gray.6` = removed/neutral; series that are arbitrary NAMES (statuses, board columns, people) wear no
  meaning and draw from the seven-hue `CATEGORY_PALETTE` instead (never teal/orange/red/grape); the final-scope blue is per scheme
  (`useComputedColorScheme`: `flow.8` light, `flow.4` dark). Every mark must clear WCAG 1.4.11
  (≥ 3:1) on white and the `#f5f7fb` canvas (light) and the `#2e2e2e` paper and `#1f1f1f` canvas
  (dark); `chartColors.test.ts` recomputes the ratios from the theme (the file's comment holds the
  table: e.g. flow.6 3.56 on white, teal.8 3.44 on dark paper, red.7 3.53, orange.8 3.79, gray.6 3.32
  on white; flow.8 is 2.70:1 on dark paper, hence `flow.4` there). Adjacent series in ONE chart must
  also differ enough to tell apart (the dataviz validator's ΔE ≥ 15 floor): never two shades of one
  hue side by side (commitments' initial/final pair is a known exception — legend, tooltip and table
  carry it; its user mode separates committed from final by fill and position, not by a second blue), and stacked segments are ordered so orange and red never touch (sprint consistency:
  carried over · delivered · dropped, teal between). One question per chart: sprint consistency is
  three small charts (committed vs delivered; the stacked final = carried + delivered + dropped;
  added vs removed), not one fourteen-series chart. A report's own resolution param (`bucket`) is a
  managed filter param, serialized into the query key; a page that must label data by the request
  that produced it returns that request's param WITH the report (throughput's `bucket`), so a
  refetch over kept data never mislabels the old rows.
- **Drill to items** (server: `.claude/docs/reports.md` "Drill to items"; a bar's number is always one click from the work behind it).
  *State*: ONE page-local URL param, `drill` (`utils/reportDrill.ts`: `DRILL_PARAM`, `serializeDrill`/`parseDrill` — `bucket:2026-03-02` (also `sprint:…`, `hist:…`, Costs' `cost:ACTIVITY_TYPE:Development` and `cost-sprint:<teamId>:<connectionId>:<sprintId>[:e=…]` (the retired epic table's `epic:` spelling is no target any more), and Home's `home:<VIEW>:<teamId>` and `week:<teamId>:<monday>`, Person trend's `point:<rowKey>:<windowEnd>`), plus
  `;s=<accountId>` for one person's segment; a hand-edited value that does not parse is ignored), owned by `hooks/useReportDrill(filterKey, anchor)`. Like
  throughput's `view` it is no filter — never sent with the report request, never in a query key — and `reportHref` drops it on EVERY tab hop (a bar means
  nothing on another report). Opening is a history PUSH (location state `drillOpened`), so the page stays mounted and the filters, scroll position and chart
  view are untouched; the drawer's X and Escape call `close()` — `navigate(-1)` when this page opened it, else (a deep link) the param is dropped
  in place — and browser Back pops the entry. A person narrowing (`setSegment`) is a REPLACE; a change of the serialized filter closes an open drill (replace),
  because the bar no longer exists — except the page's own normalisation of a link: `useReportPage` hands the pages `filterKey`, null until the reference data is in, and the first real key is the baseline, so a team-less `drill=` link keeps its drill when the team-required report gives it the default team (`ReportDrillLinkWithoutTeam.test.tsx`, one case per team-required drill report). A bar of kept (placeholder) data never opens a drill under the new filter (`drillable`): the chart handlers are dropped, while the tables render the drill figures as plain text, not buttons (`drillFigureColumn`'s `disabled`, `ReportSprintsTable`'s `drill.disabled`, the tables' `drillDisabled`, `DistributionPanel`'s too: the histogram pages pass `onDrill` always and `drillDisabled={!drillable}`; the layout does not jump, since the figures keep their text). *Focus*: the hook records what had
  focus when the drill opened and restores it when the param goes (whichever way), else focuses the element carrying `data-drill-anchor="<anchor>"` — the
  `ReportChartCard` with `drillAnchor` (`tabIndex={-1}`), the pointer route's return target; the Drawer is rendered with `returnFocus={false}` so the hook is the one owner.
  *Surface*: `components/ReportDrillDrawer.tsx` — a wide right-hand `Drawer` (`size` `calc(min(100vw, 90rem, max(60rem, 75vw)))`: the whole screen on a phone, three quarters of it from 60rem to 90rem, so the widest table -- Issue · Kind · Summary · Assignee · Sprint completed · figure -- no longer scrolls sideways; the X labelled, `trapFocus`) headed by the bar: its own `h2` (the dialog's name, `drillDrawerTitle`: 1.5rem, bold) holds the WHAT as the title and the bucket / sprint / person as a dimmed subtitle line (`drillDrawerSubtitle`) under it, with a visually hidden " · " between them so the accessible name reads "Delivered · 2026-09-07" (`title`/`subtitle` props; a sprint bar is "Committed" over the sprint, a cost slice the slice over its breakdown, a Compare people cell the view over "A · Ann");
  inside, the SERVER's own bar line ("5 items · 12.5 MD", the recomputed `bar`), an orange light `Alert` "The data changed since the chart was drawn." when the
  chart's count for that bar differs from `bar.count` (`chartCount`), a person `Select` ("Everyone" / "1. Ann" …, from two people on) in people mode, the items in a
  `ScrollRegion` (`fill`: the drawer's content, body and both stacks are one flex column -- `drillDrawerContent`/`drillDrawerBody`/`drillDrawerFill` in `theme.module.css` -- so the region is as tall as its table until the pane runs out, then scrolls inside; no fixed max-height; Issue · Summary · Assignee · Done · the bar's figure — `drillItemColumns`, a dash is an unestimated item; issue keys are `JiraKeyLink`s, see "Jira links"), a
  dimmed "Showing the N largest of M items" when `truncated`, a loader or `ErrorAlert` (no `placeholderData`: a new bar is a loader). There is no footer button: the X, Escape and the browser's Back are the ways out.
  Each report supplies a thin `<Name>DrillDrawer` (the query `["reports", <report>, "items", reportQuery(filter), serializeDrill(target)]` over `api/reportDrill.ts`'s wrapper, the
  title, the chart's count). *Triggers*: a double-click on a bar or stacked segment — each drillable chart takes `onBarDoubleClick({ rowId, series })` and
  gives every `<Bar>` `barDoubleClickProps(series.name, handler)` (`utils/chartDrill.ts`: a pointer cursor and recharts' `onDoubleClick`, `entry.payload.id` = the plotted
  row's string `id`) through the `barProps(series)` function form, and its wrapper is `user-select: none` so the dblclick selects no label — AND, as the keyboard and
  screen-reader route, the **figures of the chart's table**: each figure a drill bucket stands behind is a link-styled `<button>` (`utils/drillFigure.tsx`'s `drillFigureColumn` around
  `components/DrillFigure.tsx`; `UnstyledButton` + the `drillFigure` class: text colour, underlined, the accent ring on keyboard focus) whose `aria-label` (`reports.drill.figureAria`: "{{figure}} — {{column}}: show the items of {{name}}", e.g. "20 — Initial (MD): show the items of Alpha 2 · Committed") STARTS with the visible figure (WCAG 2.5.3 label in name; `drillFigureColumn` reads the figure from the column's `render` and the column from its header) and then names the column and the row (and bucket), and which hands itself to `useReportDrill`'s `open(…, { trigger })`, so focus returns to it on close. A figure of ZERO items (nothing to list), and every figure while the rows
  are kept data of a previous filter (`drillDisabled`), is plain text. Where two figures stand behind one bucket (MD and items) both are buttons; the figure and the column in the name tell them apart (tests and e2e locate by the column: `showItems(target, column)` in `src/test/drill.ts`). The name is the `aria-label`, never a visually hidden span inside the button, so a cell's text stays the figure alone. There is no Items column and no row menu. A chart table
  and its chart always agree on the bar's identity (the row `id`). The hint under the card ("Double-click a bar to list the items behind it — or click a figure in the
  table.", `reports.drill.hint`/`hintRows`) is `ReportChartCard`'s `hint`. Tests: the chart mock probe resolves `barProps(series)` and fires its `onDoubleClick`; the hook, the drawer, the codec and each
  page pin the URL, the request and the focus.
  *Sprint bars* (Commitments, Throughput's sprint chart, the Home chart, and the two by-epic charts, whose target carries an `:e=<epicSegment>[,<topEpicId>…]` tail — "Commitments" and "Throughput" above): the target kind `sprint:<teamId>:<sprintId>:<BUCKET>` (the seven `SprintBucket`s, the API's `bucket` enum; the team is part of the identity because several connections to one Jira site carry the SAME sprint id under different teams, and a unit-wide chart such as Home draws them all) opens
  `components/SprintDrillDrawer.tsx` -- one drawer for every sprint bar, taking the `fetchItems` wrapper (`getSprintScopeItems`, or `getThroughputItems` for Throughput's sprint
  mode) and the `scope` that keys its cache; its title is "<sprint> · <bucket>" (the sprint labelled as the chart labels it, `sprintLabeller`), its figure column is the bucket's
  "Estimate (MD)" and its date column the sprint's completion. A sprint bar is one sprint of the period, so the request carries the page's filter with the period replaced by
  `sprintId` and the team set to the BAR's own (`sprintDrillFilter`; the server requires the team, a sprint id being shared by one site's teams, `ReportDrillSprintTeamPinTest`). The figure columns of a sprint table carry the bucket they stand behind (`SprintColumn`'s `drill: { bucket, items }`, `items` = the count that decides whether the figure is a button): "Show the
  items of <sprint> · <bucket>". `ReportSprintsTable` takes `drill={{ onDrill, disabled }}`; Commitments' Initial (MD, items) → COMMITTED and Final (MD, items) → FINAL (`commitmentsFigureDrill`, also in the share
  table), Sprint consistency's seven figure columns → their seven buckets, Throughput's Delivered (MD, items) → DELIVERED (bucket table, sprint table and share tables), Home's Initial/Final/Delivered → COMMITTED/FINAL/DELIVERED. *Sprint consistency* (`pages/ReportSprintConsistency.tsx`): every chart series IS one bucket, so a double-click on any bar or stacked segment of the three
  charts (`SprintConsistencyChart`'s `onBarDoubleClick`; rows carry `id` = `<teamId>:<sprintId>`) opens that sprint's bucket -- `committedMd` → COMMITTED, `deliveredMd` → DELIVERED, `carriedOverMd` → CARRIED_OVER,
  `droppedMd` → DROPPED, `addedMd` → ADDED, `removedMd` → REMOVED (`consistencyHitTarget` in `utils/sprintDrill.ts`); FINAL is the one figure no series draws (the stacked bar's total height), so the
  sprints table's **Final** figure is the keyboard route and the only way to FINAL. The page has no member control, so there are no segments and no person
  select; its hints are the shared `reports.drill.hint`/`hintRows`; the drawer is compared with the table's own item count (`consistencyDrillCount`); each of the three chart cards and the table card has its own `drillAnchor` (`consistency-scope|partition|changes|table`), passed to
  `open(…, { anchor })`; with an activity/work-category slice the drawer repeats the page's "live figures" note as its `caption` (the list is read live like the figures). Home has no filter bar: `useReportDrill("home", …)`, the sprint request is the sprint alone (its team travels with it) and the drawer is mounted by the page
  (so a deep link opens once the overview has loaded). `useReportDrill`'s `open(target, { trigger, anchor })` passes the pressed element (focus returns to it) and/or the chart card a page with several cards returns focus to.
  *Which charts drill, and which do not.* Costs' two donuts (a slice or its legend figure) and its two sprint widgets (the capacity chart's bar, a by-epic segment; "Costs" above). Every bar chart: Throughput (period and sprint), Commitments (team and user mode, and its by-epic chart), Throughput's by-epic chart, Sprint consistency (three charts), the Home
  charts (the sprint chart, every small multiple, the weekly deliveries and the PRED points) and every `DistributionHistogram` consumer that passes `onDrill` -- Cycle time (four, epics block included), Reported time (two), Task accuracy (two),
  Epic accuracy (two), Estimate adjustments (two) and Blocked time (two). NOT offered: Data quality's late-logging histogram (its `worst` list already shows the latest-logged
  worklogs), the WIP / Estimated backlog areas, Epic progress and the Deep dive burn-up lines, the cycle-time trend and the aging scatter (not bar charts; WIP and the trend
  are decomposable at query time and would reuse `ReportDrill.kt` as a follow-up), Throughput's cumulative areas (its bucket table's Delivered figures still open the slot), Commitments'
  capacity line, Deep dive cells and the exclusion counts under a histogram. The server-side reasons: `.claude/docs/reports.md` "Drillable charts, and the ones that are not offered".
- **Dates are days in the configured zone**: `GET /reports/filters` returns `timeZone`
  (`metrics.settings.time_zone`), the zone the server reads `from`/`to` in; the bar's "today", presets
  and date-picker maximum come from `todayIsoDate(filters.timeZone)`, and dates render through
  `utils/formatDate.ts` (`YYYY-MM-DD`, never `toLocaleString`; `formatDate(ms, fallback, timeZone)`
  reads the calendar day in that zone — UTC only where no zone is passed);
  MD through `utils/reportFormat.ts`'s `formatMd` (≤ 2 decimals; the Deep dive's `formatFigure` and the burn-up's whole-hundredths plan share its
  noise-cleaned `roundScaled` rule — one rounding rule, never a second `Math.round(x * 100) / 100`). The `from`/`to` span cap, text cap and integer
  parsing the two URL filters share live in `utils/filterPrimitives.ts`; `DAY_MS`/`addDays` in `utils/isoDate.ts`.

## Telemetry panel (`components/TelemetryPanel.tsx`, `components/Telemetry*Section.tsx`, `api/telemetry.ts`)

ADMIN only end to end (server: `observability.md` "Telemetry", `GET /api/v1/telemetry/current|history`, `requireAdmin`): Flow's own vital signs in a right-hand
`AppShell.Aside` on EVERY page (`breakpoint="lg"`: 420 px beside the content from 1200 px up, which gives way; below it the panel covers the page edge to edge, because a pushed
420 px next to the 240 px navbar left a sliver at tablet widths; hidden in print via `print.css`). **The toggle** is `hooks/useTelemetryPanel.ts` (`flow.viewSettings.telemetry.panelOpen`, default closed): a `useSyncExternalStore` over
localStorage, because three places act on one flag - the ADMIN-only "Telemetry panel" item of `UserMenu` (a check mark and `aria-current` while on, the language
items' idiom) and the ADMIN-only palette action in `CommandPalette` ("Show/Hide telemetry panel") write it, `App.tsx`'s `Shell` reads it. `Shell` renders the
`Aside` only for `useAdmin() && open`, so **a stored `true` never shows the panel to a non-admin** and nothing is requested; the panel is `React.lazy`, so a
non-admin never downloads it either. Closing (the panel's own X, `aria-label` "Close", the menu item or the palette action) moves focus to `#main-content` (`setTelemetryPanelOpen(false)`). The panel has
its OWN `ErrorBoundary` (with `fallback` = `TelemetryPanelCrash`: "Close panel" clears the flag), because it sits outside the route boundary and its open flag survives a reload, so a crash
would otherwise loop with no way out.

- **`TelemetryPanel`** is mounted only while open, so its one `current` query (`["telemetry","current"]`, `refetchInterval` 60 s, `refetchIntervalInBackground`
  off, `staleTime` 30 s) stops with it; a failed background refresh over existing figures shows a small `ErrorAlert` ("may be out of date") above them, and a `429` (the per-user telemetry bucket) is an ordinary inline load error with no retry. The header: title, "Sampled at <newest sample> UTC", the close button and the 24 h / 7 days / 90 days
  `SegmentedControl` (`telemetry.range`, stored). Below: an orange `Alert` per stale process ("no sample for more than three intervals"), then an
  `Accordion multiple order={3}` (an h3 per section under the h2 title, an h4 per process) of six sections (Memory, Storage, Sync, DERIVE, Requests, JVM), the expanded set stored (`telemetry.sections`, default Memory); both stored
  values are validated, a corrupt one falls back. A section's body is rendered once it has been opened (so it can finish its close transition), but its history is requested only while it is expanded (`active`).
- **Sections** (one file each, all under the 400-line cap): Memory and JVM list each process (`TelemetryProcessHeader`: role, instance, a "Stale" badge, when it
  was sampled) with a key-value `TelemetryValueTable`. Memory prints the container limit, the working set and its **share of the limit with a TEXT badge**
  from 80 % ("High", orange) and 90 % ("Critical", red) - never colour alone, nothing below 80 % - and, outside a container, "No limit" with the note that none is
  measured. Storage: the database and per-schema sizes, then a per-data-source table of issues and "Size ≈" (the estimate; `—` before the first). Sync: per data
  source the age of the last good sync (against the answer's `generatedAt`, so it is deterministic), consecutive failures (red badge + the error code), Jira 429s of
  the last 24 h (orange badge + the back-off), the queue and the last backfill's duration. DERIVE: last run (teal/red status badge), duration, the week's longest run,
  rows written. Requests: the window, a total row and the busiest routes (count, p50, p95, 5xx red badge, 429 orange badge). Requests labels the percentiles with their own window (`percentileWindowSeconds`). Wide tables sit in `ScrollRegion`. A stale replica listed beside a live one of the same role keeps its own table and badge.
  Empty states, in the sections' words: no process yet or `sampledAt: null` -> "No sample yet: the first one arrives within N min of a process starting"; no
  container limit; `storage: null` -> "Storage is measured hourly: not yet"; `requests.measuredAt: null`; no data source.
- **The trend** (`TelemetryTrend`, per section, history per `["telemetry","history",group,range]`, `staleTime` 5 min, only while the section is expanded): the pure
  `utils/telemetryTrend.ts` turns the series into `TrendColumn`s (`memoryColumns`, `storageColumns`, `requestsColumns`, `jvmColumns`, `jobColumns`) - the PLOTTED ones
  share one unit (one axis), the table holds every column; a RATIO series is shown in percent, a role suffix tells web from worker only when both sampled.
  Memory draws the working set as a SHARE of the container limit per role when every role's limit is known (the limits come from the current answer; the table adds the bytes), else the bytes (the heap when there is no container), Storage the database and each schema, Requests p50/p95 of the `*` total, JVM the CPU,
  Sync and DERIVE the mean duration of ONE data source picked in a `Select` (`TelemetryJobTrend`; the table adds runs, failures - zero where the server left the
  series out - 429s, queue peaks or rows written). The chart (`TelemetryTrendChart`, a lazy chunk, `@mantine/charts` `LineChart`, `CATEGORY_PALETTE` colours, a legend
  from two lines on, `connectNulls` off) carries a labelled group; its numbers are in a `DailyTableDisclosure` ("Show the figures") because 420 px leaves no room beside it.
  Notes: "History starts <date>" when `historyFrom` is later than the range's start; "Left out of the chart: <names>" for `omittedConnectionIds` (names from the current answer, else `#id`); a generic note when `truncated` dropped nothing else. Requests series carry no role (every serving role is merged). All times are UTC: the chart's accessible name and tooltip say so, and a 24 h axis shows the date once the window crosses midnight. Role suffixes use the role's words (`roleName`).
- **Formatting** is `utils/telemetryFormat.ts` (bytes in binary B/KB/MB/GB, durations ms..d, percentages, counts with a no-break space between thousands), dates
  `utils/formatDate.ts`. Copy: `locales/*/telemetry.json` (the `telemetry.*` area). Tests: the section/panel/trend/toggle tests are co-located, the shared answers are
  `src/test/telemetryFixtures.ts`, and the charts are mocked like everywhere. Under the test provider Mantine's `Collapse` may mount a panel body twice in its first
  render, so a test of the history requests compares the SET of URLs. e2e: `e2e/tests/telemetry.spec.ts`.

## Jira links (`components/JiraKeyLink.tsx`, `hooks/useJiraConnections.ts`, `utils/jiraLinks.ts`)

Every issue or epic KEY a report prints is a `JiraKeyLink`: an `Anchor` to `{siteUrl}/browse/{encodeURIComponent(KEY)}` with
`target="_blank" rel="noopener noreferrer"`, a decorative external-link icon (`aria-hidden`) and visually hidden "(opens in Jira)"
(`common.jira.*`, EN + PL), inheriting the surrounding size and weight. The site is `connections[].siteUrl` of
`GET /reports/filters` (any signed-in user; the organisation's own Jira origin, not a secret -- `reports.md`), read PASSIVELY from the
`["reports", "filters"]` query-cache entry (`useJiraConnections`, a `useSyncExternalStore` over the cache: it never fetches, so a key
in a bare component test costs no request and turns into a link when the entry arrives). Every report page and the Deep dive
already load that entry; `RawIssueInspector` loads it once a result shows. A trailing slash is stripped. **Which site**
(`resolveJiraSite`): a row that knows its `connectionId` (an above-epic item, the inspector's route) uses that connection's site;
every other report row carries no connection, so the site is the one every connection with a site SHARES -- a single Jira, or
several connections to it -- and with two different sites a key stays plain text rather than link to the wrong Jira (a
`connectionId` on those server shapes is the follow-up that lifts it). An unknown site (filters not loaded, a blank `siteUrl`)
is plain text too. Where a key is also an in-app link (Epic progress's epic rows -> the epic scope) the Jira link is an
icon-only link BESIDE it (`iconOnly`, named "<KEY> (opens in Jira)"). Where it:
the drill pane (`ReportDrillDrawer`, hence Throughput/Commitments/Sprint consistency/Cycle time/accuracy/adjustments/blocked/
reported time/Costs/Compare people drills; `drillItemColumns`' optional `renderIssueKey`), Aging WIP and Blocked time
item tables, Epic accuracy's epic table, Data quality's task, epic, above-epic and late-logging lists, Epic progress's epic
rows, the Deep dive's "Totals per epic and task" table (the key text) and its grid's row headers (an icon beside the
toggle, `skipTab`: the grid is ONE roving tab stop and the keyboard has the same links in the summary table), and the raw issue
inspector's heading. The Deep dive pickers' option labels and chart tooltips stay text.
