# Frontend feature pages

Read on demand, in full, before touching a data-source, metrics-config or report page in `web/`; the conventions that apply to every page stay in `web/CLAUDE.md`.

## Data sources (`pages/DataSources|DataSourceDetails|DataSourceProfile|RawIssueInspector.tsx`)

The first Jira-domain surface (v0.2.0 plan §9/§10), ADMIN-only end to end: the nav leaf
(`IconPlugConnected`, `adminOnly`), the four routes (`/data-sources`, `/data-sources/:id`,
`…/:id/profile`, `…/:id/inspect`, all under the same `RequireAdmin` group as Users/Feature flags
in `App.tsx`; every page's queries additionally stay `enabled` only for `useAdmin()`), and every
endpoint (`requireAdmin` server-side).

- **`api/dataSources.ts`** mirrors `api/teams.ts`'s shape — list/create/update/delete plus the two
  Test-connection wrappers (`testDataSourceAdHoc`/`testDataSourceStored`) and `requestSyncJob`
  and the per-connection reads/actions: `listSyncJobs`/`cancelSyncJob` (`…/sync-jobs`),
  `getDataSourceStatus` (`…/status`), `getRawIssue` (`…/raw-issues/{issueKey}`) and
  `getDataSourceProfile` (`…/profile`) — every type derived from `schema.ts`. Unlike Teams, `DataSourcePage.items` already carries the FULL `DataSourceResponse`
  (minus the write-only token) — Edit opens straight from the row, no extra detail fetch.
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
  `siteUrl` is a `409` server-side; that also means a saved `409` here is always the name clash,
  so it marks the `name` field like Teams does). Fields: name, site URL (`https://<tenant>
  .atlassian.net` hint), service-account email, a `PasswordInput` token ("leave blank to keep the
  current token" on edit — blank travels as an omitted `apiToken`, which the server keeps), a
  `TagsInput` for project keys (force-uppercased in `onChange`, after `form.getInputProps`, so the
  wire value always matches `PROJECT_KEY_PATTERN`), a plain backfill-date `TextInput`
  (`YYYY-MM-DD`, blank omits the field so the server computes its 24-months-back default — no
  `@mantine/dates`), sync interval / reconcile hour `NumberInput`s, an auth-scheme `Select`
  (Basic/Bearer), and an enabled `Switch`. **`utils/dataSourceForm.ts`** mirrors
  `ingest/DataSource.kt`'s validation exactly (name/site-URL/email/token length caps, the Jira
  site-URL and project-key regexes plus the Atlassian-reserved-label rejection, the
  backfill-date range) — keep the two in sync.
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
  per still-open row). **Auto-refresh is conditional**: the status query's `refetchInterval` is 5s
  only while `currentJob` is open, `false` otherwise — never a fixed poll (the server's `currentJob` is the open
  job — RUNNING, else the PENDING one the worker would claim first, `SyncJobsService.openJob` — so a just-requested job shows its badge and is
  polled from the first fetch). The sync-jobs history query polls on that condition
  (`jobOpen`) AND while any visible history row is PENDING/RUNNING, so a row that is not the summary's job (a second
  PENDING one) is still followed to Running and on to its terminal state; it also refetches once more when `jobOpen` falls
  (a ref-tracked open→idle transition), so its last row shows the terminal state even if the tick that raced the
  summary read it as Running. `keepPreviousData`/paging are untouched (the interval refetches the current key).
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
  query only runs once a key is present. Shows the canonical raw payload, tombstones, changelog/
  worklog payloads, and — once processed — the `norm.*` work item, its status/field intervals and
  anomaly badges (orange, the fixed `TilingAnomaly` vocabulary). A `400`/`404` renders INLINE under
  the form, never replacing the page, so the admin can simply try another key.
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
`updateMetricsSettings`, the `/jira-memberships` CRUD, `listJiraUsers`, and
`getDataSourceMetricsConfig`/`updateDataSourceMetricsConfig`/`getDataSourceMetricsConfigOptions`
— every type derived from `schema.ts`.

- **`pages/MetricsSettings.tsx`** (`/metrics-settings`, `adminOnly` nav leaf `IconAdjustments`,
  under the same `RequireAdmin` route group as Users/Data sources) is the ONE global
  `metrics.settings` singleton form — the CreateUser/EditUser template (`Paper withBorder p="xl"
  maw={FORM_MAX_WIDTH}`, `form.initialize` guarded by `!form.initialized`, a full-replace PUT,
  `showSuccessToast` on save, an inline Alert on failure). `utils/metricsForm.ts` mirrors
  `metrics/MetricsSettings.kt`'s `validateMetricsSettings` field for field (including the
  aging-percentiles-must-include-85 rule and the all-weekend rejection) and holds the form's own
  string-array reshaping: `weekendDays`/`agingPercentiles` travel as `Chip.Group`/`TagsInput`
  string values client-side, mapped to `number[]` only in `toMetricsSettingsRequest`. The zone
  picker is a searchable `Select` over `Intl.supportedValuesOf("timeZone")`
  (`utils/metricsForm.ts`'s `supportedTimeZones`, cached). The success toast reads "Saved — reports
  re-derive shortly" — accurate once M3's DERIVE job lands, so the wording needs no follow-up
  change.
- **`components/TeamJiraMembers.tsx`**, mounted on `pages/TeamDetails.tsx` below the Flow-login
  roster — D1's dated Jira-user team membership (`GET/POST /api/v1/teams/{id}/jira-memberships`,
  `PUT/DELETE …/{membershipId}`): any authenticated user reads the table (person name resolved
  against `GET /api/v1/jira-users?scope=UNIT` — every account that ever held a membership row is
  UNIT-relevant, so the directory, paged through at pageSize 100 to its `total` (`api/metrics.ts`'s
  `listAllJiraUsers`, capped at 50 pages, `staleTime` 60 s, abortable — React Query's `signal` reaches every
  page fetch, so an unmount ends the walk), names this team's whole history however large the unit
  is; an unresolved account falls back to its raw `accountId`; a failed walk renders its load error inline
  and one the page cap cut short shows a gray `directoryCut` notice; adding a member also invalidates
  `["jira-users","directory"]` so a newly added SITE person is named at once), a "current" badge when `now ∈ [validFrom, validTo)`.
  ADMIN-only mutations: "Add Jira member" opens `components/JiraMemberModal.tsx` (a searchable
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
  configuration") over `Tabs`: Statuses (stage `Select` + a Blocked `Checkbox` per status, the
  Jira category as a `Badge`; under that table `components/DomainStageOverrides.tsx` — a "Per-domain
  overrides" section: a domain `Select` over the Domains tab's CURRENT domain keys (each option counts its
  overrides), then a table with one row per status — the every-domain stage, a stage `Select` whose empty value reads
  "Same as all domains" (= no row; clearing it or the row's remove button deletes the override) and a "Differs"
  badge when the override's stage is not the every-domain one; a domain key renamed on the Domains tab leaves its
  overrides orphaned, flagged by a red inline `Alert` per orphan, and so is an override on a status the connection no longer reports (an `Alert`
  with a Remove button — such an override has no row in the status table and would `400` every save), and the server's `400` shows in the page alert
  — they save with the ONE Save, as `domainStatusStages`), Fields (five `Select`s over the profile-detected custom fields plus
  Jira's own `duedate` system field, labelled with the detected role), Domains (project key →
  domain key/name), Boards → team (an active-teams `Select`; a `409` marks the changed board
  row(s) inline, the "which row" rule computed by diffing the just-submitted board→team snapshot
  against the last-loaded/last-saved one — `utils/metricsConfigForm.ts`'s `changedBoardIds`),
  Activity types (issue type → activity type) and Work categories (value → category, fetched with
  `?workCategoryField=` the moment a work-category field is chosen on the Fields tab — shown only
  once one is). Capacities (one `NumberInput` per sprint, blank = the computed default) rounds out
  the seven. GET returns computed defaults when `configured: false` — a banner says so ("Showing
  computed defaults — save to confirm them") until the first save. ONE Save does a full-replace
  PUT of every tab's current state (even an untouched tab still submits its own default), a
  success toast reads the same "reports re-derive shortly" wording as `MetricsSettings.tsx`, and a
  `400`/`409` renders the SERVER'S OWN `detail` message inline (not a fixed-vocabulary mapping —
  these messages are admin-facing by design, `.claude/docs/metrics.md`'s validation/conflict
  rules). **`components/MappingTable.tsx`** is the one generic id → editable-cell table every
  mapping-shaped tab reuses (a `Select`/`TextInput`/`Checkbox` per field column, the row's own
  identity in a fixed left column) — callers own all state, MappingTable is a pure renderer.
  **`utils/metricsConfigForm.ts`** holds the pure state-shaping: `buildInitialState` merges the GET
  response with the options endpoint's reference lists into one row per reference item (mapped or
  not), `buildRequest` is its inverse, `mergeWorkCategoryValues` combines the field-scoped values
  query with whatever category is already chosen, and `changedBoardIds` is the 409 row-marking
  rule; its override helpers (`setDomainStage`, `currentDomainKeys`, `orphanOverrideDomains`) keep the list free of
  duplicate (domain, status) rows.

## Reports (`pages/ReportVelocity.tsx`, `components/Report*.tsx`, `utils/reportFilter.ts`)

The v0.3.0 report pages (`.claude/docs/reports.md` is the API; every report is any-authenticated,
D12 — routes sit under `RequireAuth`, never `RequireAdmin`). Sixteen pages carry the seventeen reports
(report 13, the backlog in sprints, rides the Estimated backlog page): the shell (Reports nav
section, `ReportTabs` — real router links with `role="tab"`, so middle-click opens a report in a new tab), the filter bar, the shared blocks and the three tab groups — Delivery
(Velocity, Throughput, Sprint consistency, Cycle time; `DELIVERY_TABS`), Estimation (Task accuracy,
Epic accuracy, Adjustments, Reported time; `ESTIMATION_TABS`) and Flow metrics (WIP, Estimated
backlog, Aging WIP, Blocked time, Epic progress; `FLOW_TABS`) — plus the Data quality and Cost
matrix pages. A new report appends a page, a tab (the tab lists live in
`utils/reportLinks.ts` — each group's nav leaf lists every tab route in `NavLeaf.activeFor`, so it
stays highlighted on all of them) and a `reports.<name>` key block. Every page composes the same
skeleton — `hooks/useReportPage` (filters query → keyed page query), `ReportFiltersStatus`,
`ReportFilterBar`, `ReportMetaNote`, `ReportChartCard`, `ReportSprintsTable` (sprint · team ·
completed · the report's figures · the orange drift badge with the frozen figures as text),
`ReportGroupsTable` — copy `pages/ReportVelocity.tsx`.

- **The URL is the filter** (`utils/reportFilter.ts`, `hooks/useReportFilter.ts`): `from`/`to`,
  `lastSprints`, `sprintId`, `teamId`, `accountId`, `domainView`, `domain`, `activityType`,
  `workCategory`, `bucket`, `connectionId` — deep-linkable, parsed forgivingly (an invalid or
  conflicting param is DROPPED, never sent; the period is exclusive with precedence `sprintId` >
  `lastSprints` > dates), serialized in one canonical key order (that string is also the page query
  key). Params this module does not own survive `applyReportFilter`; switching report tabs
  (`reportHref`) drops the report-specific params (`domainView`, `domain`, `activityType`,
  `workCategory`, `bucket`) the target report has no control for, so a filter the user
  cannot see or clear never follows them (`REPORT_SPECIFIC_PARAMS` in `utils/reportLinks.ts`; `epicId` is one of them, kept only by epic progress; a page's normalizer drops the rest through `dropReportSpecific(filter, reportSpecificKeep(path))`, so the table stays the one source). A team id the reference data no longer lists prints `#<id>` through `teamLabel` (`utils/reportFormat.ts`). Presets are stored as
  absolute `from`/`to` dates (calendar days in the configured zone) and recognised again by
  `activePeriodChoice`. **The last team is remembered** (`useStoredState`, `reports.teamId`): a report
  opened with NO filter params at all (a bare nav click) starts on it and the URL is rewritten
  (replace) to say so; a link that carries any filter param is taken as written (a copied unit-level
  link stays unit-level). Only the Team control touches the memory (picking stores, clearing clears).
- **`components/ReportFilterBar.tsx`** is a controlled component (`filters`, `filter`, `onChange`);
  optional controls (domain view "delivered in / earned in", domain, activity type, work category,
  and the week/month `bucket` `SegmentedControl`) render ONLY where the report passes them
  in `controls` — velocity and sprint consistency pass none, throughput passes domain view, domain,
  activity type, work category and bucket. Every report is also a
  command-palette entry (`REPORT_PALETTE_LEAVES`, palette-only: the sidebar carries one Delivery leaf).
  The bar is only the frame: `ReportPeriodControls` (with the `hooks/usePeriodChoice` hook), `ReportScopeControls` (team, member, domain, connection and the sprint-survival rule) and `ReportSliceControls` (+ `ReportSliceSelects`: activity type, work category and the segmented toggles) own the controls, `ReportFilterSelect` is their shared clearable select, and `ReportControls` lives in `utils/reportFilter.ts`. The generic `components/ColumnTable.tsx` (`ColumnDef`: header, render, align; `GroupColumn`/`SprintColumn`/`QualityColumn` are aliases of it) backs only three tables today (`ReportGroupsTable`, `ReportSprintsTable`, `DataQualityCard`); a new report table should use it, while 30 older files (the other report tables and the non-report lists) still hand-roll a Mantine `Table` (`Table.Thead`).
- **Distributions** (`components/DistributionPanel.tsx`): a `Distribution` renders as a percentile
  strip (median p50, p90, p95, mean, item count), a lazy `DistributionHistogram` (single blue series,
  no legend) and the histogram as a table; `hidden` (fewer items than `meta.minSampleSize`)
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
  Task and epic accuracy show the at-start view first (D15: the primary view) and the at-done view
  beside it; an epic's own estimate is never replaced by its child sum (both are columns). The epic
  accuracy page offers only domain and work category (an epic's domain is its own space under either
  domain view, and it has no activity type); the other two estimation pages offer the domain view too.
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
  narrows `teamId`). No finished epic is a one-line note, and USER level is the `EpicsPerPersonNote`. Reported time explains in its description that the ratio is "how much of
  the elapsed working time was logged" (actual MD ÷ cycle working days), distinct from flow
  efficiency (active ÷ cycle time, shown as %, no "no time logged" bucket — so the two panels keep
  separate accountings), and reads the median first (the outlier note: very short cycles dominate
  the mean, p95 and the histogram's top; they are never dropped).
- **Flow metrics group** (`pages/ReportWip.tsx`, `pages/ReportBacklog.tsx`, `FLOW_TABS`, the "Flow metrics" nav leaf
  `appShell.nav.reportsFlow` — deliberately not just "Flow", which the brand text already is): the two
  snapshot reports over the daily aggregates. Their bar offers only `domain` (never activity type/work category —
  `400` server-side) and `domainExcludesTeam` makes the LAST of team/domain touched win (the aggregate has no
  team × domain split; picking a domain therefore clears the remembered team, like any team clear), and a
  pasted link with both is normalised on load — the team wins — by `useReportPage`'s `normalize` argument
  (`dropDomainWithTeam`, `normalizeWipFilter`), which rewrites the URL too so a dropped param cannot come
  back when another control changes. WIP adds two
  managed params, `by` (stage · status · board column) and `itemKind` (tasks · epics · both), both always SENT
  explicitly (`by=STAGE&itemKind=TASK` by default); `by=COLUMN` needs one team (`wipColumnAvailable`) so the
  option is disabled with a hint otherwise and a stray `by=COLUMN` is dropped from the URL (falls back to
  STAGE), and a `400` for a request that WAS for columns of one team is the "no board mapped" note, not a
  failure (any other 400 is the normal failure alert). The chart is a stacked `AreaChart` of end-of-day counts with band
  toggles (`Chip.Group`, local state): stages keep their vocabulary colours (gray/blue/teal/orange); statuses
  and columns are arbitrary names, so they never wear a semantic hue: `BAND_CYCLE` alternates blue and gray
  (flow.6, gray.6, flow.7, gray.6 — the only neutral shades that clear 3:1 on all four surfaces), assigned by
  position among the bands SHOWN (`paintBands`) so neighbours never share a hue; beyond two bands a hue/shade
  repeats and the legend, tooltip and tables carry identity. The ticked-bands state is per keying (and per team
  for columns); STAGE starts with Not started and Done hidden (Done only
  grows, Not started is the whole backlog). Its text alternatives are a per-band summary table and the full
  daily table behind `DailyTableDisclosure` (a series can run to ~1100 rows). The backlog page is three tiles
  (MD, items, "≈ N sprints ahead" — a dash plus the reason when there is no velocity or the mean is 0,
  "< 0.1" instead of "≈ 0"; at UNIT level the wording says "at least N closed sprints per team", since the pace
  is a sum of team means and `sprintsUsed` their minimum) and a
  one-series MD `AreaChart` whose tooltip and table add the item count. The server's `note` (not derived yet,
  USER level, …) is prose in English only: `ReportNote` shows it verbatim under a translated title, except the "Not derived yet" note when
  `meta.derivedAt` is null (the translated meta line already says it).
- **Aging WIP and blocked time** (`pages/ReportAgingWip.tsx`, `pages/ReportBlockedTime.tsx`, tabs 3 and 4 of
  `FLOW_TABS`). Aging WIP is "as of now": its bar passes `noPeriod` (no period control) and the request drops
  any period param (`withPeriod(filter, {})`; the URL keeps it so tab hops preserve it). It shows the
  thresholds as a tile row per kind (`AgingThresholdsRow`; epics only when there are epics or a window; hidden
  thresholds = a `MinSampleNotice` with `subject="thresholds"`) above the open-work table, in the server's
  order (never re-sorted). **The band is the SERVER's** (`"P85"`, `"WITHIN"`, `null` = hidden) — never
  recomputed from the age; `utils/agingReport.ts`'s `bandTone` only reads its RANK among the configured
  thresholds of the item's own kind: the top threshold red ("above p95"), the next orange ("above p85"), the
  rest gray — the text always names the threshold, colour is never the only carrier. Blocked is a red outline
  badge with the word. Issue keys are plain text (no page links to Jira). Blocked time composes
  `DistributionWithAccounting` twice (blocked working days over EVERY finished item, so no exclusions and a
  "N of M were blocked at all" line; share of cycle with `neverStarted`/`zeroCycle`), the top-20 table and the
  groups table; `itemKind` (default TASK, always sent) reuses the WIP bar control, and at TEAM level with epics
  a line says the per-assignee groups cover tasks only.
- **Epic progress (EVM)** (`pages/ReportEpicProgress.tsx`, the fifth tab of `FLOW_TABS`; `.claude/docs/reports.md` "Report 15").
  The URL carries at most ONE scope — `epicId` (an epic's issue key, a report-specific managed param that only this
  report keeps), `domain` or `teamId` (`0` = UNASSIGNED) — and `normalizeEpicProgressFilter` (`utils/epicProgressReport.ts`,
  the `useReportPage` normalize hook) makes a pasted link answerable: epic over domain over team, and `domainView`,
  `accountId`, `activityType`, `workCategory` (each a `400`) plus the ignored `bucket`/`by`/`itemKind`
  dropped — off the request and off the URL. The bar passes `domain` + `domainExcludesTeam` + `noMember` (no user
  level) and the page routes its changes through `applyBarChange`, so the LAST scope touched wins, epic included.
  Levels are drilled by the row NAME links (`scopedSearch` re-scopes the same report, the period travelling along); a
  domain → epic drill carries the domain in the router `state` so the breadcrumb (`EpicProgressBreadcrumb`) can offer the
  way back — a directly opened epic simply has only the unit above it. Tiles: PV/EV/AC, SV/CV signed
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
  a group of one, so no `ReportTabs` and no palette-only twin; `.claude/docs/reports.md` "Report 14"). One card per finding
  (`components/DataQualityCard.tsx` the shell: h3 title, one plain-language line, a state badge — orange `Found: N`, teal `None found`, gray
  `Not measured`, none for the logged-hours figure — and `CappedTable`, which says "and N more" when `total > items.length`), in the order
  logging (`DataQualityLogging`) → missing data and epics (`DataQualityMissing`) → sprint/drift (`DataQualitySprint`) → configuration
  (`DataQualityConfig`), the overview tiles (`DataQualitySummary`, each a link that scrolls to and focuses its card — no hash in the URL)
  first and the groups table last. A clean finding keeps its card (users see it was checked). The bar offers period, team/member, domain,
  domain view and — only with more than one connection — `connection` (`ReportControls.connection`); `normalizeDataQualityFilter` drops
  every param the page has no control for (activity type, work category, bucket, by, item kind, epic) off the request AND the
  URL. What the API returns is rendered as is: a real team sees no domains/authors without a team (the card says a team's view lists none),
  the connection-level findings say the team filter does not narrow them, USER level says epic and sprint findings are not read for one
  person, and a work-category field nobody configured is "Not measured", not clean. Configuration findings are admin-actionable: the
  connection cell links to `dataSourceMetricsConfigPath` for `useAdmin()` only, plain text for everyone else. Snapshot drift prints the
  sprint, translated figure, live, frozen and the signed difference (`utils/dataQualityReport.ts`), marking `reconstructed` baselines with
  an orange light badge and one explanatory line.
- **Cost matrix** (`pages/ReportCostMatrix.tsx`, `/reports/cost-matrix`, its own single-page nav leaf `appShell.nav.reportsCost` — a
  group of one like Data quality, since a matrix is a different shape from the flow reports' series, so no `ReportTabs` and no palette-only
  twin; `.claude/docs/reports.md` "Report 16"). Tiles (man-days, foreign man-days, the overall foreign share — a dash and the reason when the
  server sends `null`, never 0%), then `components/CostMatrixTable.tsx`: a HEAT TABLE — rows are the level's authors (UNIT: author teams,
  UNASSIGNED `teamId` 0 last under the shared "Unassigned" label; TEAM: the team's authors, the no-author bucket "No known author"; USER: one
  row), columns the domains (`domainView` default EPIC, the bar's "Delivered in / Earned in" toggle; the caption line says whose domain the
  columns are), cells the man-days shaded on ONE sequential scale (`utils/heatScale.ts`: the brand blue mixed into the table surface in five
  steps, linear against the largest CELL — `heatStep` — with each step's own text colour, pinned ≥ 4.5:1 in both schemes by
  `heatScale.test.ts`; a zero cell has no fill and dimmed text; the number is the carrier and a legend says so), then row total, foreign MD
  and foreign share (`formatPercent`, a dash for `null`) and a totals row. Every figure is the server's own rounded exact sum, NEVER re-added
  from the cells shown (the footnote states the ≤ 0.005-per-addend rule). It is a real table: a visually hidden `caption`, `scope="col"`/
  `scope="row"` headers, the first column and the header row sticky inside a `ScrollRegion` (`theme.module.css` `.heatTable` — the
  theme's card frame clips with `overflow: hidden`, which would keep `position: sticky` from engaging, so the class lifts it). A row NAME is
  the way in (`drillSearch` in `utils/costMatrixReport.ts`: team → its authors, author → one person, the period travelling along; a one-sprint
  period survives only into a team that lists the sprint); a soft-deleted team (`active: false`) is marked "Deleted team" and never linked
  (its drill is a `400`), and UNASSIGNED is not linked under a sprint-relative period (`meta.from === null` — a team-less drill resolves no
  sprint and answers empty). A sprint-relative period says so: the unit's team rows read the union envelope, a team drill only its own
  sprints. The bar offers period, team/member, domain, domain view, activity type, work category and (with more than one) connection;
  `normalizeCostMatrixFilter` drops `bucket`, `by`, `itemKind` and `epicId` off the request and the URL.
- **Deep dive** (`pages/ReportDeepDive.tsx`, `/reports/deep-dive`, its own single-page nav leaf `appShell.nav.reportsDeepDive` — a group of one like
  the cost matrix; `.claude/docs/reports.md` "Report 17", A29). **The URL is the selection and nothing else** (`utils/deepDiveFilter.ts`: repeated
  `sprintId`/`epicId`/`issueId`, `domain`, `connectionId`, `from`/`to`; forgiving parse that keeps ONE mode by precedence, canonical order, sorted
  values — that string is the page query key); layer switches (`Switch`es for plan, execution, cost) are remembered per viewer
  (`useStoredState`, `reports.deepDive.layers`, all on by default, a corrupt value falls back), drill state is local to the matrix. **No selection**
  (or an incomplete one in a pasted link) shows an explainer of the three modes and requests nothing; the report is requested only for a complete
  selection, with NO kept previous data (a new selection is a loader, never a stale grid) and `DeepDiveMatrix` is keyed by the serialized selection
  so a cached answer for another selection never inherits an open month. Failures are inline: a `400` prints the server's own `detail` (too many
  tasks, an unknown epic — admin-grade prose, shown as written) under "This selection cannot be shown", anything else the shared `ErrorAlert`.
  **`components/DeepDiveSelectionPanel.tsx`** (draft logic in `utils/deepDivePanel.ts`): a `SegmentedControl` for the modes — Sprints of a domain
  (domain `Select` from the shared filters' domains + sprint pickers, max 52), Epics (max 50), Tasks of an epic (an epic `Select` + task picker, max 500);
  the pickers are `components/DeepDivePicker.tsx`: a searchable `Select`/`MultiSelect` over a server list, the typed text debounced 300 ms into `q`,
  the combobox's own filter switched off (the server already matched key AND summary, each on its own), picked values kept in the data (labelled by
  any option answer the picker has seen, else by the loaded report's names, else the value) so they survive any search, a "N of MAX selected" line,
  a "showing the first N of M" line when the page (100) was cut, a hint while the picker waits for a domain or epic, the previous answer kept on screen
  while the next loads (same scope only), the spinner on the LEFT so the clear button stays, and the picker's own failure inline. **Whether a
  single-choice box is being searched is decided by the viewer's own keystrokes (`onInput`, ended by selecting, clearing or leaving it), never by
  comparing its text with the label** — Mantine writes the selected label into the box itself, and a label comparison made it fight that text with a
  request every debounce window. The panel holds a DRAFT and only the explicit
  **Show** button writes it to the URL (a push, so Back works; the selection already shown pushes nothing) — typing and picking fire no report
  request, and Show does NOT rebuild the panel: the page resets it (a new `key`) only when the URL changes from outside (Back/forward, a followed or pasted
  link), never for the change its own Show made. A blocked Show stays focusable (`aria-disabled`, activation ignored) and its `aria-describedby`
  live line says what is missing for the mode ("Pick a domain and at least one sprint", …); a link's `connectionId` the reference data does not list is
  dropped from the draft; switching the mode drops every pick
  (they belong to the old mode) and keeps connection and dates, a new domain or epic, or another connection, drops the picks listed under the old one (the connection keeps the domain); the
  connection select exists only with more than one active connection; `From`/`To` are optional `YYYY-MM-DD` text inputs (clip) whose malformed or
  reversed/over-1100-day values name the problem and block Show. A `NOT_DERIVED` answer is one gray note and no grid; `RANGE_CLAMPED` an orange note
  above the grid (both translated — the server's English `note` is shown verbatim only for any other kind); under the matrix sit the **data limits**
  (`components/DeepDiveLimits.tsx`: the six `quality` counters, zeros included, each with a one-line why, then the fixed rules — as-was domain,
  sub-tasks roll up, status-based execution without blocked time, a worklog on its start day — and the freshness day `range.asOfDay`).
  **The matrix** (`components/DeepDiveMatrix.tsx`, model and sums in `utils/deepDiveMatrix.ts`): a `<table role="grid">` in a `ScrollRegion` with a
  roving tab stop (arrows, Home/End), drill headers that are buttons with `aria-expanded` (months → ISO weeks → days, epics → tasks), a polite live
  region for each drill, ONE tooltip on hover or focus (Escape closes it), every cell's `aria-label` carrying its numbers, and the legend, a summary
  table and the visible figures behind `DailyTableDisclosure` as the text alternative. Contrast and identity: plan (`flow.6`) is a full-width bar,
  execution (`teal.8`) two-thirds, cost (`gray.6`) one-third, each semi-transparent with a solid 2 px edge that carries the 3:1, so colour is never the
  only cue; plan and cost share one man-day scale and execution has its own (the legend says so). **The 25k guard:** more visible cells (rows ×
  columns) than 25,000 — or more figures in the bucket table than that — replaces the grid with a notice asking to collapse or narrow, so the DOM stays
  bounded however large the answer.
  **The Burn-up tab** (`components/DeepDiveBurnup.tsx`, its lazy chart `DeepDiveBurnupChart.tsx`, the sums in `utils/deepDiveBurnup.ts`): Mantine `Tabs`
  "Matrix" | "Burn-up" under the selection, ONE fetch for both (the burn-up is client-side over the same answer). The tab lives in the URL as
  `view=burnup` (`parseDeepDiveView`/`applyDeepDiveView` in `utils/deepDiveFilter.ts`; the matrix, the default, is the param's absence; `view` is a
  foreign param to the selection functions, so Show and the page query key keep it; a tab hop is a `replace`); the matrix panel stays mounted (its
  drill state survives a hop), the burn-up renders only while open, and the layer switches live in the matrix panel only. The chart is a `LineChart` of
  CUMULATIVE PV (`flow.6`), EV (`teal.8`), AC (`gray.6`) per day, one man-day axis, each starting at 0 on `range.from` and counting only what falls in
  the shown range (a note says so; whole-life totals stay in the matrix summary), so a line's end equals the matrix model's `inRange` total. AC is the
  tasks' cost plus the epics' own worklogs (EPICS mode; a note says so). EV and AC are actuals and end on `range.asOfDay` (`null` after it, `connectNulls`
  off; a later entry extends them) while PV runs on, and the as-of day is a labelled dashed `referenceLines` marker. The optional dashed plan-blue **epic
  budget plan** (a `Switch`, off by default and remembered per viewer — page state under `reports.deepDive.burnupBudget`, so it survives a tab hop — offered only when some epic
  has a planned window and a budget) spreads each epic's budget over the working days of its window with report 15's running `ROUND(total * i / n, 2)`
  rule; a window reaching outside the range keeps its full length and the line shows the part inside the range — for the days outside the shown range it
  assumes a Monday–Friday week and no holidays (the configured weekend and holidays are known only inside it); it covers whole epics, however many
  tasks are picked, and is a separate comparison line, never part of a PV total. The as-of label is text, so it takes `AS_OF_COLOR` (`gray.7` light,
  `gray.4` dark, ≥ 4.5:1 on each scheme's surfaces) and hangs off its line toward the side with room (`insideBottomRight` in the range's right half). The daily table (newest first, a dash where an actual has ended) sits behind `DailyTableDisclosure`; week/month aggregation is not
  offered, the x axis thins its ticks instead.
- **Home overview** (`pages/Home.tsx`, plan amendment A9; `utils/homeOverview.ts` is its pure logic). The landing page is
  the WHOLE unit at a glance — never the remembered team, the page description says so — as four tiles over UNIT-level
  report endpoints, **five requests and no aggregator** (`["home", <report>]` keys, staleTime 60 s; the budget is pinned by
  `Home.test.tsx`): the shared `["reports","filters"]` reference data (same key as the report pages, so opening a report reuses
  it; only its `timeZone` is read, nothing waits on it and its failure shows nowhere), `sprint-consistency?lastSprints=1` (each team's last closed sprint, all from one `fact_sprint` row:
  committed = initial, final, delivered; a lazy `HomeVelocityChart` beside a table with a Closed date read in the configured zone and the
  orange "Drift" badge + frozen figures like the report pages; title → Velocity, secondary links → Throughput and Sprint
  consistency), `cycle-time` (the server's default trailing 90 days: median/p90/finished count and `CycleTimeTrendChart` at
  `height={200}` with the trend as a visually hidden table), `aging-wip` (tasks in progress, and "Past p85" orange /
  "Past p95" red as the top two configured thresholds' counts — read off the SERVER's `band` through `bandTone`, epics
  not counted, dashes plus a reason when the thresholds are hidden, "N+" when the 500-item list was truncated) and
  `data-quality` (`qualityHighlights`: the configuration kinds — derive warnings, unmapped statuses/boards, domains
  without an owner — first, then the volume kinds by count, five shown as orange badges, a plural "and N more kind(s)",
  "All findings"; teal "None found" when clean). `HomeTile` is the shell: a section whose h3 title IS the link into
  the full report, a caption stating the period/scope, `aria-busy`, and its OWN skeleton/error triage (one failing report
  never takes another tile down); the page owns the ONE polite "Loading…" live region. **No link is ever bare** (a bare report
  URL applies the remembered team): every one carries `lastSprints=1` or a from/to period — the one the server resolved
  (`overviewPeriod`: `meta.from/to` of the cycle-time or data-quality answer), else the same trailing 90 days computed
  locally in the configured zone (UTC dates while the reference data is pending or failed). When every answer says `derivedAt: null` and none failed the page shows the empty state instead
  (admin: data sources + metrics settings links); a failed request keeps the grid so the empty state never hides it.
- **Load order**: `["reports","filters"]` (staleTime 60 s) → the page query keyed
  `["reports", <report>, <serialized filter>]`, `enabled` once the filters loaded,
  `placeholderData: keepPreviousData` (`ReportChartCard` dims the previous body and sets `aria-busy`).
  `ReportChartCard` owns the load/empty/error triage; the row NAME in `ReportGroupsTable` is a
  `RouterLink` narrowing `teamId` (UNIT) or `accountId` (TEAM) — "name is the way in".
- **Chart rules** (ported from Lettuce): charts live only in lazy chunks (the page lazy-imports its
  chart component), each chart component imports `@mantine/charts/styles.css` itself, a legend for
  two or more series (none for one), one axis (never dual), and every chart has its numbers in a
  table beside it (velocity and the sprint charts: the per-sprint table; throughput's period chart:
  `ThroughputBucketTable`). Colour is never the only carrier of identity — legend, tooltip and table are. Tests mock `@mantine/charts` (recharts draws nothing under happy-dom) and assert
  the props. **Colour vocabulary, no new hue** — concrete shades in `utils/chartColors.ts`: blue `flow.6` =
  plan/committed, teal `teal.8` = delivered, orange `orange.8` = carried over/added scope, red
  `red.7` = dropped/blocked, gray `gray.6` = removed/neutral; the final-scope blue is per scheme
  (`useComputedColorScheme`: `flow.8` light, `flow.4` dark). Every mark must clear WCAG 1.4.11
  (≥ 3:1) on white and the `#f5f7fb` canvas (light) and the `#2e2e2e` paper and `#1f1f1f` canvas
  (dark); `chartColors.test.ts` recomputes the ratios from the theme (the file's comment holds the
  table: e.g. flow.6 3.56 on white, teal.8 3.44 on dark paper, red.7 3.53, orange.8 3.79, gray.6 3.32
  on white; flow.8 is 2.70:1 on dark paper, hence `flow.4` there). Adjacent series in ONE chart must
  also differ enough to tell apart (the dataviz validator's ΔE ≥ 15 floor): never two shades of one
  hue side by side (velocity's initial/final pair is a known exception — legend, tooltip and table
  carry it), and stacked segments are ordered so orange and red never touch (sprint consistency:
  carried over · delivered · dropped, teal between). One question per chart: sprint consistency is
  three small charts (committed vs delivered; the stacked final = carried + delivered + dropped;
  added vs removed), not one fourteen-series chart. A report's own resolution param (`bucket`) is a
  managed filter param, serialized into the query key; a page that must label data by the request
  that produced it returns that request's param WITH the report (throughput's `bucket`), so a
  refetch over kept data never mislabels the old rows.
- **Dates are days in the configured zone**: `GET /reports/filters` returns `timeZone`
  (`metrics.settings.time_zone`), the zone the server reads `from`/`to` in; the bar's "today", presets
  and date-picker maximum come from `todayIsoDate(filters.timeZone)`, and dates render through
  `utils/formatDate.ts` (`YYYY-MM-DD`, never `toLocaleString`; `formatDate(ms, fallback, timeZone)`
  reads the calendar day in that zone — UTC only where no zone is passed);
  MD through `utils/reportFormat.ts`'s `formatMd` (≤ 2 decimals).
