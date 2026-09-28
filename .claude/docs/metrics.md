# Metrics layer (v0.3.0)

This doc grows commit by commit alongside the v0.3.0 metrics-layer work (the phase-3 plan,
`.claude/docs/domain-model.md`). It starts with the configuration model — the ADMIN-curated inputs
every later DERIVE run reads under ONE recorded revision. The derivation algorithm, the star schema
mechanics and the report API each add their own section as they land (see the plan's commit
sequence — commits 7-19).

## Configuration model

Two layers of configuration exist, both change-tracked through the SAME shared
`metrics.settings.config_revision` counter (`metrics/MetricsConfigService.kt`):

- **Global settings** (`GET/PUT /api/v1/metrics-settings`, v0.3.0 M1 commit 3) — the ONE
  `metrics.settings` singleton: the calendar (time zone, weekend days, holidays), `hoursPerDay`,
  commitment grace, minimum sample size, the aging-WIP window/percentiles, the backlog-in-sprints
  window and the epic-drift threshold. See `MetricsSettingsResponse`/`Request` in
  `metrics/MetricsSettings.kt`.
- **Per-connection configuration** (`GET/PUT /api/v1/data-sources/{id}/metrics-config`, v0.3.0 M1
  commit 4) — everything that is specific to one Jira connection's own data shape: status → stage,
  the estimate/epic-date/work-category field choices, project → domain, board → team, activity
  type and work-category maps, blocked statuses and per-sprint capacity overrides. This section
  covers that resource.

Both `metrics/TeamMembership*.kt`'s dated Jira-user team membership (D1, v0.3.0 M1 commit 3, its
own doc coverage in `.claude/docs/domain-model.md` "Configuration") and the per-connection resource
below bump the SAME shared revision — a DERIVE run (arriving with commit 7) always reads the
`norm` facts under ONE recorded `config_revision`, so every derived number names exactly which
configuration produced it (D-invariant 12, "every live number is reproducible from `norm` + one
configuration revision").

### Per-connection metrics configuration (`GET/PUT /api/v1/data-sources/{id}/metrics-config`)

**ONE composite resource, not eight paired endpoints** (API-RES-004, the features-PUT idiom
`metrics/MetricsSettings.kt` already established): a PUT is a full replace over all eight
per-connection `metrics.*` config tables (`.claude/docs/persistence.md` "V15") in ONE transaction —
`metrics/MetricsConfigService.kt`'s `replaceConfig`. The composite shape
(`metrics/DataSourceMetricsConfig.kt`):

```
DataSourceMetricsConfig {
  configured: Boolean
  statusStages: [{ statusId, stage }]              // metrics.status_stage_map, domain_key = ''
  fields: { estimateTask, estimateEpic, epicStart, epicDue, workCategory }  // metrics.field_config
  domains: [{ projectKey, domainKey, domainName }]  // metrics.domain_map
  boards: [{ boardId, teamId }]                     // metrics.board_team_map
  activityTypes: [{ issueType, activityType }]      // metrics.activity_type_map
  workCategories: [{ valueId, valueName, category }]// metrics.work_category_map
  blockedStatuses: [statusId]                       // metrics.blocked_statuses
  sprintCapacities: [{ sprintId, capacityMd }]       // metrics.team_sprint_capacity
}
```

**`GET` on an unconfigured connection returns the computed DEFAULTS**, `configured: false` — never
a `404` and never an empty shell an admin has to fill in from scratch before reports work at all
(team attribution excepted, since a board → team mapping can only ever be an admin's own choice):

| Field | Default |
|---|---|
| `statusStages` | seeded from each status's Jira category (`norm.statuses`) — `new → NOT_STARTED`, `indeterminate → IN_PROGRESS`, `done → DONE`; a status whose category is `UNKNOWN` is left OUT of this list (flagged, never guessed — `.claude/docs/domain-model.md`'s "an unmapped status is flagged, never guessed"). The `metrics-config/options` endpoint's own `statuses` list still names every status Jira reports, mapped or not, so an admin can see the gap. |
| `fields.estimateTask` / `fields.estimateEpic` | the connection's stored data profile's `STORY_POINTS`-detected custom field id (`source_connections.profile.customFields`, `jira/JiraProfile.kt`) — both roles share the one detected field |
| `fields.epicStart` | the profile's custom field whose name contains "start date" (case-insensitive); if none, the field whose name contains "target start" (Jira Plans' own start-date field, for a company-managed-project tenant using Plans dates instead of a team-managed "Start date" custom field); `null` if neither was detected |
| `fields.epicDue` | `"duedate"` — Jira's plain system field, always a valid choice, never subject to profile detection |
| `fields.workCategory` | `null` — must be chosen; there is no sensible guess for which field carries it |
| `domains` | 1:1 — one row per project key observed in `norm.work_items` (LIVE rows only), `domainKey`/`domainName` both set to the project key itself |
| `boards` | empty — no board → team mapping exists until an admin makes one |
| `activityTypes` | 1:1 — one row per issue type observed in `norm.work_items` (D6: activity types are standard issue types) |
| `workCategories` | empty |
| `blockedStatuses` | empty — only the Flagged field counts as "blocked" until an admin adds statuses here |
| `sprintCapacities` | empty — DERIVE (commit 8) falls back to a computed default (A3: members × working days) when no row exists for a sprint |

**Validation — the client-supplied-FK idiom, `400` never `404`** (there is no path id inside the
body): every id `DataSourceMetricsConfigRequest` carries is checked against exactly this
connection's own reference data (`metrics/DataSourceMetricsConfig.kt`'s `MetricsConfigReferenceData`,
computed by `MetricsConfigService.replaceConfig`/`.referenceData`):

- `statusStages[].statusId` and `blockedStatuses[]` → `norm.statuses` (`WorkItemStore.allStatusRefs`).
- Every non-null field id (`fields.*`) → the stored data profile's `customFields` ids, plus the one
  hardcoded system field `"duedate"`.
- `domains[].projectKey` → distinct project keys observed in `norm.work_items`
  (`WorkItemStore.distinctProjectKeys`) — NOT the connection's own configured `projectKeys`
  setting, since the reference is "what Jira actually reports", not "what scope was requested".
- `boards[].boardId` → `norm.boards` (`WorkItemStore.allBoardRefs`); `boards[].teamId` → the ACTIVE
  teams registry (`teams/TeamService.Teams`, cross-feature read, listed in `.claude/docs/persistence.md`).
  D10 ("one board per team") is enforced the OTHER direction at the database:
  `uq_metrics_board_team_map_team_id` (a `23505` → `409`, already named in
  `plugins/ErrorHandling.kt`'s `UNIQUE_CONSTRAINT_DETAILS`).
- `activityTypes[].issueType` → distinct issue types observed in `norm.work_items`
  (`WorkItemStore.distinctIssueTypes`).
- `workCategories[].valueId` → the distinct values `norm.work_items.custom_fields` carries for
  the REQUEST's OWN `fields.workCategory` field id (`WorkItemStore.distinctCustomFieldValues`) —
  never validated against a field the request doesn't itself choose; submitting `workCategories`
  while `fields.workCategory` is `null` is itself a `400`.
- `sprintCapacities[].sprintId` → `norm.sprints` (`WorkItemStore.allSprintRefs`); `capacityMd` must
  be `>= 0`.

**A repeated key WITHIN one array of the SAME request** (two `statusStages` entries naming the same
`statusId`, two `domains` entries naming the same `projectKey`, and so on for every list-shaped
field except `boards[].teamId`) is a `400`, checked BEFORE the reference-data lookups
(`requireNoDuplicateKeys`, `metrics/DataSourceMetricsConfig.kt`) — a malformed REQUEST, not a
conflict with something else already stored. `boards[].teamId` is the deliberate exception: two
boards claiming the same team, even within one request, stays D10's "one board per team" `409` at
the database (above), never pre-empted by this check.

**The no-op-PUT precedent** (the features-PUT idiom, `MetricsSettingsService.replace`'s own rule):
a PUT byte-for-byte identical to what is already stored writes nothing, bumps no revision, and the
route emits no `metrics_config.updated` audit line. `MetricsConfigService.readStoredConfig` returns
`null` when NOTHING is stored across all eight tables — that `null` is what flips `configured` to
`false` and is also the "nothing to compare against, this PUT is a real first write" signal. The
comparison is order-INSENSITIVE (`DataSourceMetricsConfigRequest.canonicalized()` sorts every list
by its own natural key before comparing): a plain `SELECT` with no `ORDER BY` gives no row-order
guarantee, and a re-PUT of the SAME set in a different array order must stay a no-op — `readStoredConfig`
itself still applies a deterministic `ORDER BY` per table so a `GET` response is stable across
repeated reads, but that ordering is a presentation nicety, not what the no-op check relies on.

**`GET /api/v1/data-sources/{id}/metrics-config/options`** — the reference data the metrics-config
editor picks from (`DataSourceMetricsConfigOptions`): every status (with its Jira category, mapped
or not), the profile-detected custom fields (with their `detectedRole` —
`SPRINT`/`RANK`/`TEAM`/`STORY_POINTS`/`FLAGGED`/`OTHER`, `jira/JiraProfile.kt`'s own detection,
reused rather than reimplemented), distinct project keys, boards, distinct issue types, and sprints
(all boards' sprints together — a future UI groups them per board client-side). `workCategoryValues`
is populated ONLY when the caller supplies `?workCategoryField=<fieldId>`, from that field's own
distinct observed values across the connection's LIVE work items
(`WorkItemStore.distinctCustomFieldValues`) — a Jira select-field value's shape (`{id, value}`, a
bare string, or an array of either) is parsed defensively, never cast blindly, since the caller may
name ANY custom field, not necessarily a `select`. The result is capped at 200 distinct values
(alphabetically by id) — `workCategoryValuesTruncated: true` means more exist, a backstop against a
poorly-chosen high-cardinality field (e.g. a free-text field picked as `WORK_CATEGORY` by mistake)
growing the response unbounded; `PUT`'s own id validation against this same reader is unaffected by
the cap (a legitimate value beyond the first 200 is still accepted, since the config table itself
has no such limit — only the OPTIONS listing is capped).

### PURGE and the metrics config

A soft-deleted connection's PURGE job (`.claude/docs/ingestion.md` "Job orders") drains its
per-connection `metrics.*` config rows through a GENERIC, connector-agnostic worker step —
`MetricsConfigService.purgeConnectionConfig`, called from `ingest/IngestWorker.kt`'s `runJob` right
after the connector's own `purgeSteps` finish. This is deliberately NOT wired into
`JiraConnector.purgeSteps`: the eight tables it drains hold no Jira-specific shape at all (a future
GitLab connector's connection would purge through the exact same call), so the cleanup lives beside
the OTHER connector-agnostic PURGE work the worker itself already owns, not duplicated per
connector kind.

## The DERIVE run algorithm (v0.3.0 M3 commit 7)

`metrics/MetricsDeriver.kt`'s `derive(context)` is the `DERIVE` job body
(`.claude/docs/ingestion.md` "The DERIVE job kind"): read the effective per-connection config +
global settings (one transaction, so a racing settings write can never let this run stamp a
revision newer than the config it actually derived under — review round 1), prune old
`derive_runs` rows (`MetricsStore.pruneDeriveRuns`, the `SyncJobsService.prune` shape, run on
`ingest.jobRetentionDays`), insert a `RUNNING` `derive_runs` row, then run the WHOLE rebuild inside
ONE `suspendTransaction`: delete every rebuildable `metrics.*` row for the connection, upsert
`dim_date` over `[min(created_at) − 1y, now + 2y]`, insert `dim_domain` (small, config-derived,
inserted once), then three ordered passes over the connection's LIVE `norm.work_items` rows.

**Memory (review round 2b).** Earlier drafts loaded every work item's FULL `custom_fields` object
plus every issue's status/field intervals, field changes and worklogs for the WHOLE connection at
once. The fix has two parts:

- **`custom_fields` is trimmed to only the configured field ids** (`estimateTask`/`estimateEpic`/
  `workCategory`/`epicStart`/`epicDue`) right after `workItemsForDerivation` reads it back — a real
  tenant's Rank/ADF-shaped fields (`.claude/docs/persistence.md`'s "Known storage cost" note)
  otherwise duplicate raw bytes for every issue the connection-wide `itemsById` reference map holds.
- **Per-item work runs in batches of `DERIVE_BATCH_SIZE` (200) issues**, in two ordered passes, each
  reading its OWN interval/field-change data scoped to that batch's issue ids
  (`WorkItemStore.statusIntervalsByIssue`/`fieldIntervalsByIssue`/`fieldChangesByFieldIds` all
  accept an optional `issueIds` filter for exactly this), computing that batch's rows, and inserting
  them before the next batch's own read begins — never holding the whole connection's raw
  intervals/field changes in memory at once:
  - **Pass 1** (every item, tasks and epics alike): reads STATUS + FLAGGED intervals and the
    configured estimate field's changes for the batch, computes each item's `ItemDerived` (stages,
    started/done, reopens, blocked intervals, estimate timeline, work category) and inserts that
    batch's `item_stage`/`item_blocked`/`item_estimate` rows. `derivedById` (the computed RESULTS,
    not raw intervals — far smaller) accumulates across every batch, since later passes need it for
    epic/sub-task cross-references.
  - **Pass 2** (TASK items only, i.e. `hierarchyLevel != 1`): reads ASSIGNEE + SPRINT + PARENT
    intervals and `issuekey` field changes for the batch, builds `task_epic`/`task_domain`/
    `task_assignee` history and `dim_task`/`fact_task_delivery` rows from the ALREADY-computed
    `derivedById` (no fresh raw reads needed there), and inserts them. `factTasksByIssueId` (small
    fact rows, not raw data) accumulates across every batch for pass 3's epic roll-up.
  - **Pass 3** (EPIC items only): no per-batch raw reads at all — an epic's own facts come entirely
    from `derivedById`/`factTasksByIssueId`/config, already in memory; builds and inserts
    `dim_epic`/`fact_epic_delivery` per batch.
  - **Worklogs are a connection-wide AGGREGATE, not per-issue rows**: `WorkItemStore
    .worklogSecondsByIssue` returns a plain `Map<Long, Long>` (summed `time_spent_seconds` per
    issue) via one query — this commit's algorithm only ever SUMS worklog seconds (a task's own
    plus its sub-tasks', an epic's own plus its children's `actual_md`), so the full per-worklog row
    shape (author, timestamps — reserved for commit 9's `fact_worklog`) is never loaded here.

The whole rebuild — dims, both passes, `dim_domain` — runs inside `derive()`'s ONE outer
`suspendTransaction`; batching only bounds what is held in memory at once, not the number of
transactions (Postgres has no trouble with many statements in one transaction). A second DERIVE
over unchanged input writes byte-for-byte identical rows regardless of batch boundaries, since
final content depends only on each item's own data, never on which batch it landed in
(`MetricsDerivationTest`'s reprocess-digest test, `.claude/docs/testing.md`'s pattern).

**Failure and cancellation.** A thrown exception (including a genuine coroutine
`CancellationException`, itself an `Exception` subtype) is caught once: `markRunFailed` stamps the
row `FAILED` with a truncated error detail, then the exception is rethrown so cancellation still
propagates correctly. `markRunFailed`'s own DB write runs under `withContext(NonCancellable)`
(review round 2b fix) — without it, a coroutine already cancelled by the time this catch runs would
never actually execute the `suspendTransaction` write, leaving the row stuck `RUNNING` forever. The
previously-written facts are untouched either way (the failed transaction rolled back before any
delete committed, or never started). There is deliberately no `CANCELLED` status — see
`.claude/docs/persistence.md` "The `metrics` schema — the derived star (V16)".

## Calendar math (`metrics/WorkingCalendar.kt`)

Pure, timezone-aware: `dayOf(instant)` folds an epoch millis into an ISO date string in the
configured zone; `isWorkingDay(day)` checks the configured weekend-day set and holiday set;
`workingDaysBetween(a, b)` counts working days in `[a, b)`, additive
(`wd(a,b) + wd(b,c) = wd(a,c)`); `dimDateRows(from, to)` emits one `DimDateRow` per calendar day in
range, each carrying its own UTC-millis day boundaries and working-day flag — `metrics.dim_date`'s
own row shape (`.claude/docs/persistence.md`).

## Kernel definitions (`metrics/DeriveKernels.kt`)

Pure, per-item functions — no DB, the `norm/Tiling.kt` pattern — called once per issue by
`MetricsDeriver.kt`:

- **`stageIntervals`** tiles `norm` status intervals into `item_stage` rows via the configured
  `statusId -> ItemStage` map; an unmapped status becomes `ItemStage.UNMAPPED` (flagged, never
  guessed). **`startedDoneAt`**: `startedAtMs` = the FIRST-ever entry into `IN_PROGRESS` (even
  across a later reopen); `doneAtMs` is set ONLY while the CURRENT (last, open) stage is `DONE` —
  the start of that trailing unbroken DONE run, not merely the last transition into DONE — so an
  item whose entire history sits in `UNMAPPED` is never started and never done (neither
  `IN_PROGRESS` nor `DONE`, per the domain-model rule "an unmapped status is flagged, never
  guessed" — `MetricsDerivationTest`'s dedicated UNMAPPED-status test pins this end to end).
  `reopenCount` = every DONE → non-DONE transition.
- **`blockedIntervals`** unions FLAGGED=true spans and configured-blocked-status spans, merges
  overlapping/adjacent pieces, and clips to `[startedAtMs, doneAtMs ?: now)`. Each merged
  `BlockedInterval` carries a `reason` — `"FLAGGED"` when every raw span folded into it came from
  the Flagged field alone, `"STATUS"` when AT LEAST ONE came from a configured blocked status
  (review round 2b fix: the merge used to hardcode `"FLAGGED"` on every `item_blocked` row
  regardless of source, silently losing the blocked-status reason whenever it overlapped or merged
  with a Flagged span). `DeriveKernelsTest` pins all three shapes: FLAGGED-only, STATUS-only, and a
  merged overlap (STATUS wins).
- **`estimateTimeline`/`estimateAt`/`estimateSnapshots`** build a configured estimate field's value
  history from its raw changelog changes (falling back to the text pair, since a real Jira number
  field carries its changelog value only in `fromString`/`toString`) plus the CURRENT value as the
  timeline's last, ground-truth point; `0` or missing both mean unestimated. Snapshots read the
  timeline at start/done; "estimated late" = no estimate at start but one exists now.
  **`mergeEstimateTimelines`** sums several sub-tasks' OWN timelines at every distinct change
  instant (SUBTASKS roll-up) rather than reusing each one's CURRENT sum at every past point.
- **`sprintMembership`** diffs the Sprint field's comma-joined id-list changelog text into
  per-sprint set-valued membership intervals (a carry-over move never loses the FROM sprint's own
  interval, unlike `norm`'s own last-id-only field-interval tiling).
- **`projectKeyTimeline`** replays the `issuekey` changelog field (never `project` itself, which
  carries no key) into `task_domain`'s effective-dated history.
- **`epicDriftFlags`** compares an epic's OWN stage against its children's delivery state — D11's
  three codes, flagged, never re-dating the epic.
- **`valueAsOf`** is the one generic as-of helper (a value active at an instant from an ordered
  `(changedAt, value)` list) — work category at `done_at`, a sprint's team at an instant, etc.

## Sprint scope, facts and snapshots (D13, v0.3.0 M3 commit 8)

`MetricsDeriver.kt`'s sprint step (`runSprintStep`, `.claude/docs/domain-model.md` "Plan — PV"/
"Glossary") turns each level-0, non-sub-task task's Sprint-field history
(`DeriveKernels.sprintMembership`) into `metrics.fact_sprint_scope` rows (one per task × sprint it
was ever a member of), rolls those up into `metrics.fact_sprint` (one row per sprint), and freezes a
`metrics.fact_sprint_snapshot` row the first time a closed, team-mapped sprint is seen. Sub-tasks
and epics never carry independent sprint scope of their own (`sample-data/jira/generate.mjs`'s own
`sprintItemsOf`).

**Commitment, added, removed, final, delivered, carried-over, dropped** (`DeriveKernels.sprintScope`,
one call per task × sprint membership):

- **Committed** = the task entered the sprint at or before `sprintStart + commitmentGrace`
  (`commitAt`). Its `estimate_at_commitment_md` is read AT `commitAt` (or, for a task added later,
  at its own entry instant — see "added" below).
- **Added** = entered the sprint AFTER `commitAt` (mutually exclusive with committed) — its own
  `estimate_at_commitment_md` is read at ITS entry instant, not `commitAt`.
- **Removed** — a task that WAS committed but is no longer a sprint member at `sprintCloseAt` (the
  sprint's `completeAt`, or `now` for a still-open sprint — `fact_sprint` is always the LIVE
  recomputation, D13's frozen figure is the separate snapshot below). `removed_at` is the LAST exit
  at-or-before `sprintCloseAt`; `estimate_at_close_md`/`estimate_at_done_md` stay `null` and
  `in_scope_at_close = false` — a removed row is a terminal bucket of its own, contributing to
  NEITHER committed NOR final NOR delivered/carried-over/dropped (**the removed-row rule**,
  `DeriveKernels.sprintTotals`'s own doc: "committed alone is not the committed-bucket predicate" —
  the committed TOTAL additionally requires `in_scope_at_close`, since a removed row still carries
  `committed = true` — it WAS committed, before it left).
- **Final** = in scope at `sprintCloseAt` (committed or added, never removed) —
  `estimate_at_close_md` is always read AT `sprintCloseAt`, regardless of bucket.
- **Delivered** (`done_in_sprint`) = the task's `done_at` falls inside `[sprintStart, sprintCloseAt]`
  AND it was still a sprint member at that instant. `estimate_at_done_md` is read at the SAME
  `sprintCloseAt` instant as `estimate_at_close_md` (never re-evaluated at the task's own `done_at`)
  — a task done mid-sprint is not re-estimated on the way out; the two columns carry the same
  number whenever both are set, kept separate only because the schema names them for two different
  readers (D13's snapshot figures vs. a future per-item report).
- **Carried-over** / **dropped** (A17) — evaluated for EVERY task still in scope at
  `sprintCloseAt` and not done by then, **committed OR added** (not just committed ones — every
  task reaching this branch is already in scope at close, so the predicate is simply "not done"):
  carried-over if a LATER sprint of the same board/team holds it (D10: one board per team, so
  "later sprint of this task's own board" = "later sprint of the same team",
  `laterSprintIdsPerSprint`), dropped otherwise. A carried-over task counts in the velocity (final
  scope) of EVERY sprint it was committed to, but in throughput (delivered) only once — the sprint
  where it was actually done (Jira's own convention, `.claude/docs/domain-model.md`'s Glossary).
  This makes the sprint's buckets a true partition: **`final = committed + added = delivered +
  carried-over + dropped`, always** (in item counts always; in MD as long as no item's estimate
  changed between its own entry/commit instant and `sprintCloseAt`) — before A17, an added task
  that was never done and never removed (still in scope at close) contributed to `final` but to
  NEITHER `delivered` NOR `carried-over` NOR `dropped`, silently breaking the identity.

**Point-in-time estimates.** Every `fact_sprint_scope` estimate column is a snapshot read off the
task's own estimate timeline (`DeriveKernels.estimateAt`) at a FIXED instant per bucket — never the
task's current/latest value — so a later re-estimate never rewrites what a sprint's own commitment
or close figure already recorded.

`fact_sprint` (one row per sprint) is the Σ of its own `fact_sprint_scope` rows
(`DeriveKernels.sprintTotals`, invariant 8 — true BY CONSTRUCTION, since `MetricsDeriver` writes
exactly this function's output as the `fact_sprint` row) plus `capacity_md`/`capacity_source`/`load`
(below); it is always the LIVE recomputation, rebuilt wholesale on every DERIVE.

### Default sprint capacity (A3)

`MetricsDeriver.kt`'s `sprintCapacity` resolves `(capacity_md, capacity_source)` per sprint:

1. **`CONFIGURED`** — a `metrics.team_sprint_capacity` row for this sprint always wins, verbatim.
2. **`DEFAULT`** — absent a configured row, AND the sprint's board maps to a team (`board_team_map`)
   AND it has a known `start_at`: `capacity_md = (member count) × (working days in the sprint
   window)`. Member count is every `metrics.team_membership` account whose interval overlaps
   `[sprintStart, sprintEnd)` for THIS team (`endAt = end_at ?? complete_at ?? start_at`); working
   days come from the SAME `WorkingCalendar` (global settings — time zone, weekend days, holidays)
   every other calendar computation uses. This is the doc's own "members × working days − absence"
   default, minus absence data Flow does not have (`.claude/docs/domain-model.md`'s Configuration
   table) — an admin edits the sprint's capacity via the per-connection config PUT instead.
3. **Neither** — no team mapped, or no `start_at` known: `(null, null)`. No capacity means no
   `load` either (`load = committed_md / capacity_md` only when `capacity_md > 0`).

### The snapshot rule (D13)

A `metrics.fact_sprint_snapshot` row is written the FIRST time a sprint is seen `state = 'closed'`
AND team-mapped AND has no existing snapshot row (`existingSnapshotSprintIds`) — never again: the
table is append-only (no update/delete writer exists; the `trg_metrics_fact_sprint_snapshot_immutable`
DB trigger, `.claude/docs/persistence.md` "The `metrics` schema — the derived star (V16)", is the
actual enforcement, since the invariant must hold even against a hand-run `UPDATE`). A later DERIVE
never touches an already-snapshotted sprint's row, byte-for-byte — only a NEWLY-closed sprint (one
that was still open on every earlier DERIVE) gets a snapshot written.

**`reconstructed`** = `true` when the sprint's own `complete_at` predates this connection's FIRST
EVER successful `derive_runs` row (`firstSuccessfulDeriveRunStartedAt`) — i.e. Flow never watched
this sprint live; it only ever saw it already closed, backfilled from history. `false` means Flow's
own DERIVE pipeline was already running (and had succeeded at least once) by the time the sprint
closed, so its snapshot reflects what Flow itself observed as the sprint completed, not a
reconstruction from stored history after the fact. The connection's OWN first successful run never
counts itself (it is still `RUNNING`, not `SUCCEEDED`, while it is the one computing this).

**PURGE bypass.** The immutability trigger's one sanctioned bypass is `MetricsStore.purgeAll`'s
`SET LOCAL metrics.allow_snapshot_delete = 'on'`, run only for a soft-deleted connection's PURGE job
— see `.claude/docs/persistence.md`.

### Sprint field resolution (bug fix, v0.3.0 M3 commit 8 follow-up)

The sprint step reads a task's Sprint-field changelog history through
`WorkItemStore.fieldChangesByFieldIds`, keyed by the connection's OWN detected Sprint custom field
id — `MetricsConfigService.detectedSprintFieldId` (`source_connections.profile.customFields[].role
== "SPRINT"`, the SAME profile-role lookup `defaultConfig` already uses for `STORY_POINTS`) — never
by the Jira changelog's display TEXT `"Sprint"`. A tenant that renamed or localized the field would
otherwise make the old display-name lookup silently return zero rows, and the sprint step would then
read every task as "only ever in its current sprint since creation" — corrupting every historical
sprint total with no signal. If a connection HAS sprints but its profile has never detected a
Sprint-shaped field (no PROCESS/PROFILE pass yet, or a real tenant with no Sprint field at all), the
WHOLE sprint step is skipped for that DERIVE run — no `dim_sprint`/`fact_sprint`/`fact_sprint_scope`/
snapshot rows are written — and `derive_runs.row_counts` records `sprintFieldUnresolved: true`, so
the gap is visible on the run rather than silently guessed. `MetricsDerivationTest` covers both the
resolved path (the golden sprint's scope buckets still match after resolving by id) and the
unresolved path (no fabricated rows, the flag is recorded).

## Worklog cost facts (`fact_worklog`, v0.3.0 M3 commit 9)

`MetricsDeriver.kt`'s worklog step (top-level `runWorklogStep`/`worklogRowsForItem`, moved outside
the class body the same `LargeClass` way the sprint step already is) is the LAST step of
`runDerivation`, after the sprint step: one `metrics.fact_worklog` row per LIVE
`norm.work_item_worklogs` row (`.claude/docs/domain-model.md` "Cross-team time"/D3, invariant 6/7).
It batches over only the items that actually carry a worklog (`WorkItemStore.worklogsByIssue`, a
connection-wide read — no per-issue filter exists there yet, unlike the interval readers), reading
each batch's own PARENT/`issuekey`/SPRINT intervals the same way pass 2 does — the review round 2b
memory bound applies here too.

- **`author_team_id`** — the author's `metrics.team_membership` AT the worklog's own `started_at`
  (`teamAt`, the SAME as-of helper `buildTaskRow`'s `assigneeTeamIdAtDone` uses); `null` when the
  author is in no team at that instant — the UNASSIGNED bucket is a query-time label over this
  `null`, never a stored sentinel team id (the `credit_team_id` precedent).
- **`task_domain_key`/`epic_id`/`epic_domain_key`** — read from the SAME effective-dated bridges
  pass 2 builds (`task_domain`'s history via `taskDomainHistory`, `task_epic`'s via
  `taskEpicHistory`), evaluated AT `started_at` rather than at `done_at`/now: a worklog logged
  early in a task's life is attributed to whichever domain/epic the task belonged to AT THAT TIME,
  not its current one. A sub-task has no `task_epic` bridge history of its own (the same documented
  gap `taskEpicHistory` already carries), so its worklogs use its CURRENT epic (`epicIdOf`) instead
  of an as-of value — the one place this step falls back to "current" rather than "as-of". A
  worklog logged directly on an EPIC (rare, but not filtered out) reports `task_domain_key = null`,
  `epic_id` = the epic's own id, and `epic_domain_key` = the epic's own (current) domain.
- **`activity_type`/`work_category`** — the SAME issue-type map and OWN-else-epic's-category
  fallback `buildTaskRow` uses (D6/D8) — never a separate lookup.
- **`sprint_id_at_started`/`sprint_team_id_at_started`** — the task's SPRINT field interval AT
  `started_at` (never at `done_at`, unlike `fact_task_delivery.sprint_id_at_done`), mapped through
  `board_team_map` the same way sprint-at-done is.
- **`foreign_work`** — `true` only when BOTH `author_team_id` and `sprint_team_id_at_started` are
  known AND they differ (imperfection: "Cross-team time", `.claude/docs/domain-model.md`) — never
  true off an unknown team on either side, since that would conflate "different team" with "no
  team is known here at all".
- **`late_ms`** — `max(0, created_at - started_at)`, `null` only when `created_at` is unknown (never
  in practice — the generator always sets it); a worklog entered on the same day it describes has
  `late_ms = 0`, never a spuriously negative value even if a clock skew ever put `created` before
  `started`.
- **`md`** — `time_spent_seconds / 3600 / hoursPerDay` (the global setting, default 8.0) — the SAME
  conversion `actualMdFor` already applies when rolling worklog seconds into `fact_task_delivery`.

**Invariant 6 (none dropped) and invariant 7 (no double counting).**
`MetricsDerivationTest`'s "fact_worklog - invariant 6" sweep asserts the fact_worklog ROW COUNT for
a connection equals its live-issue worklog count computed straight off
`WorkItemStore.worklogsByIssue` — every live worklog gets exactly one row, whichever
(author-team-or-UNASSIGNED, task-domain) pair it lands in. "fact_worklog - invariant 7" asserts
Σ `fact_worklog.md` equals Σ level-0 tasks' own-plus-sub-tasks' worklog seconds (converted to MD)
PLUS epics' own worklogs (worklogs logged directly on an epic, which never roll into any task's
`actual_md`) — computed as an INDEPENDENT re-derivation straight off `norm.work_item_worklogs` +
the live item set (`.claude/docs/testing.md`'s invariant-sweep pattern), rather than reading back
`fact_task_delivery.actual_md`'s own `decimal(10, 2)` column: that column's 2-decimal-place
rounding accumulates a real (if small) drift across ~1,200 issues, large enough to fail a naive
byte-for-byte comparison against `fact_worklog.md`'s finer `decimal(8, 4)` — the invariant is about
the underlying seconds never being double-counted or dropped, not about two differently-rounded
storage columns agreeing to the last decimal.

## Epic plans and PV (`fact_epic_plan`, v0.3.0 M3 commit 9b)

`MetricsDeriver.kt`'s epic plan step (top-level `runEpicPlanStep`, the sprint/worklog steps' own
`LargeClass` shape — moved outside the class, delegated to) is the LAST step of `runDerivation`,
after the worklog step: one `metrics.fact_epic_plan` row per BASELINE
(`DeriveKernels.EpicPlanBaseline`/`epicPlanBaselines`, `.claude/docs/domain-model.md` "Plan — PV",
D4, D11) — an epic's own PV plan, re-baselined whenever its start date, due date or budget
genuinely changes. `baseline_seq` (1-based) is assigned by the writer in the ORDER the kernel
returns baselines, never by the kernel itself (the `fact_sprint_scope`/`SprintScopeItem`
precedent — a pure kernel never invents a persistence-only surrogate key).

- **A baseline exists only once BOTH dates are set.** `baselined_at` is the instant the LATER of
  the two first resolves — an epic missing either date (or both) writes zero rows; this is decided
  per BASELINE INSTANT, not once per epic, so an epic whose start date is set at creation but whose
  due date only arrives later still gets its one baseline dated to when the due date landed, not to
  the epic's own creation.
- **Every LATER change to either date OR the budget opens a new baseline**, superseding the
  previous one (`superseded_at` = that instant) — a "change" that resolves to the SAME start/due/
  budget/source as the current baseline is a no-op (the `sprintScope`/`estimateTimeline` idempotence
  convention: re-submitting unchanged history must never fabricate a second baseline). **A date
  becoming unset closes the current baseline too**: the instant either date resolves back to
  `null`, the open baseline is superseded right there and NO replacement opens — the epic has no
  current plan until both dates are set again. A later instant where both resolve once more always
  opens a genuinely NEW baseline dated to THAT instant, even when its values equal the closed
  baseline's — the gap itself is a real discontinuity in the epic's plan, never silently bridged.
- **The budget resolves OWN, else CHILDREN (D4).** `0` or a missing value from the epic's own
  configured ESTIMATE field (`fields.estimateEpic`, falling back to `fields.estimateTask` when no
  epic-specific override is configured — `DeriveContext.estimateFieldIdFor`, the SAME resolution
  `fact_epic_delivery`'s own snapshots use; never the epic's START-date field, a different
  configured field entirely) count as unestimated — the SAME `estimateTimeline`/`asEstimateOrNull`
  rule applied a second time inside the kernel itself (never trusted from how the caller happened
  to build the timeline), so `budgetSource = "CHILDREN"` whenever OWN is `0`/unset, using
  `childSumMd` — a single CURRENT snapshot
  (`fact_epic_delivery.childSumEstimateMd`, already computed by pass 3, read back rather than
  re-derived) rather than a full historical child-sum timeline of its own (D4's own note: a
  child-sum roll-up over TIME would need every child's own estimate history at every past instant,
  which this commit does not build — `budget_source = CHILDREN` is always evaluated against the
  children's CURRENT sum, whichever instant the baseline itself dates to).
- **The start/due date timelines** (`DeriveKernels.dateFieldTimeline`) mirror `estimateTimeline`'s
  own shape for a plain Jira date field (`YYYY-MM-DD`, parsed the `jira/JiraNormalizer.kt` `duedate`
  way — epoch millis at start of day UTC) — built from the connection's configured
  `fields.epicStart`/`fields.epicDue` field ids' own changelog history (`WorkItemStore
  .fieldChangesByFieldIds`, batch-scoped to epic issue ids only, the review round 2b memory bound
  applied here too) plus the CURRENT value (`DeriveContext.epicDateValue`, already used by
  `dim_epic`'s own `start_at`/`due_at` columns) as the timeline's own ground truth. The epic's own
  ESTIMATE timeline is never re-read here — pass 1's `ItemDerived.estimateTimeline` is reused
  verbatim, the same way `fact_epic_delivery`'s own snapshots read it.
- **PV curve (`DeriveKernels.pvCurve`, `WorkingCalendar`-driven, not persisted this commit — read by
  reports/aggregates later).** Spreads `budget_md` evenly over the WORKING days in
  `[start_at, due_at]` (inclusive both ends), one cumulative point per working day. `start_at`/
  `due_at` are ZONE-FREE calendar dates (epoch millis at start of day UTC, the `duedate`/
  `dateFieldTimeline` convention) — read back as `LocalDate`s via UTC, NEVER the connection's
  configured zone, which would shift a UTC-midnight date a calendar day earlier for any zone behind
  UTC (e.g. `America/New_York`); only the configured weekend/holiday RULES apply to those dates
  (`WorkingCalendar.isWorkingDay(LocalDate)`, itself zone-free). The LAST working day absorbs the
  rounding remainder, so `PV(due) == budget_md` exactly regardless of how evenly the division
  splits — never a source of silent drift the way naive per-day rounding would be. A window with no
  working day at all (every day in range is a weekend/holiday) returns an empty curve.
- **An epic whose connection has no `fields.epicStart`/`fields.epicDue` configured at all** writes
  no `fact_epic_plan` rows for ANY of its epics — the same "no dates, no baseline" rule a single
  epic with unset date VALUES hits inside the kernel, applied at the connection level before the
  step even reads any field-change history.

Tests: `DeriveKernelsTest` (no baseline without both dates; the baseline dates to whichever field
resolves LAST, in either order; a later date or budget change supersedes and re-baselines; an
unchanged re-submission opens no new baseline; a date later CLEARED supersedes the open baseline
with none current, and a later re-set — even to the same value — opens a genuinely new one; the
CHILDREN fallback; a `0` own estimate treated as unestimated; `pvCurve`'s `PV(due) == budget`
exactly with a remainder-absorbing last day, its monotonicity, that weekends/holidays contribute no
point of their own, a single-day window, and that a zone behind UTC (`America/New_York`) never
shifts the window).
`MetricsDerivationTest` re-asserts the golden epic's CURRENT baseline (start/due/budget/source)
against `expected.json`'s own `golden.epic` figures and that its persisted baseline's PV curve
still ends at the budget exactly — an end-to-end wiring check, not a re-proof of the kernel's own
math. `DerivedStubFixture`'s own tripwire digest folds in `fact_epic_plan`
(`issue_id, baseline_seq` order, the surrogate `id` excluded — the same rule every other bridge's
digest line already follows).

## Derivation corrections from the measure contract (v0.3.0 M3 commit 9d)

Writing `.claude/docs/measures.md` (commit 9c) surfaced five places where `MetricsDeriver.kt` fell
short of, or simply hadn't yet implemented, the model `.claude/docs/domain-model.md`'s Amendments
A18/A19/A21 describe. This commit closes all five, backed by the additive V17 columns
(`.claude/docs/persistence.md` "The `metrics` schema — measure-contract corrections (V17)").

- **Flow efficiency (A18).** `DeriveKernels.activeWaitMs` (pure, `metrics/DeriveKernels.kt`) sums
  `IN_PROGRESS`-stage time inside `[started_at, done_at)`, subtracts ONLY the item's blocked time
  that occurred WHILE IN_PROGRESS (`overlapWithInProgressMs` intersects each blocked interval
  against the IN_PROGRESS stage intervals specifically, never the item's whole blocked time
  — review round 2c bug fix: blocked time outside any IN_PROGRESS stretch, e.g. blocked-while-
  UNMAPPED, is already WAIT by construction, since it was never summed into the IN_PROGRESS total
  in the first place — subtracting it a second time silently inflated `wait` at `active`'s expense),
  and floors/ceils the result into `[0, cycle]` (now a purely DEFENSIVE backstop, since the
  intersection can never exceed the IN_PROGRESS sum by construction) — `wait = cycle - active`,
  never negative either. A reopened item's earlier `IN_PROGRESS` stretch counts too (the window is
  the WHOLE `[started_at, done_at)`, not just the trailing DONE run's lead-up), and a blocked
  interval spanning a reopen (crossing OUT of and back INTO IN_PROGRESS) is subtracted only for the
  portions that actually overlap an IN_PROGRESS stretch, never the DONE gap in between; an item that
  never started or isn't yet done answers `(0, 0)`. `buildTaskRow` calls it once per task and writes
  `fact_task_delivery.active_ms`/`wait_ms`.
- **Current team and assignee (A21, A22).** `currentAttribution` (`metrics/MetricsDeriver.kt`)
  evaluates D5 at `context.now` rather than at `done_at`: the team of the task's current sprint —
  the norm SPRINT field interval containing `now` (its last sprint id), mapped through
  `board_team_map` — else the assignee's team (the norm ASSIGNEE interval containing `now`, then
  `team_membership` at now), via `DeriveContext.sprintIntervalsByIssue`/`assigneeIntervalsByIssue`.
  The `task_sprint` bridge is not the source (it keeps every carried-over membership open). Written for EVERY task, done or not
  (`fact_task_delivery.current_team_id`/`current_assignee_account_id`, V17) — the aging-WIP report's
  own team attribution for still-open items, since `credit_team_id` stays `null` until `done_at` is
  set. **Two A22 corrections, NOW-evaluated columns only:** a sprint whose `complete_at ≤ now` (or
  whose Jira `state == "closed"`, `MetricsDeriver.isSprintClosed`) is never treated as an open item's
  current sprint — a not-done task left in an already-closed sprint falls straight to the assignee
  fallback (effectively backlog); and both the sprint's team and the assignee's team are filtered
  through `DeriveContext.activeTeamIds` (every currently-ACTIVE team, read once per DERIVE inside the
  rebuild transaction) — a soft-deleted SPRINT team is skipped, so the assignee's team applies; a
  soft-deleted assignee team gives no team. AS-WAS columns (`credit_team_id`, `sprintTeamIdAtDone`,
  `fact_worklog`'s author/sprint/assignee teams) are untouched by either correction — they keep
  whatever team was actually true at that historical instant, soft-deleted or not.
- **As-was domain and epic attribution (A21).** `asWasAttribution` replaces the old "read the
  task's CURRENT project/epic" logic with `domainRowAt`/`epicRowAt` over the task's own
  `task_domain`/`task_epic` history (`taskDomainRows`/`taskEpicRows`, the SAME rows `buildTaskRow`
  already builds for the bridge tables), evaluated at `done_at ?: now`. **Review round 2c bug fix:**
  the covering history row itself is now returned (not just its `domainKey`/`epicId`), so a row that
  genuinely covers `atMs` with a `null` domain/epic ("this task had no epic/resolvable domain at
  that instant") is trusted AS-IS — the earlier `?:`-based fallback wrongly flattened that genuine
  `null` into the task's CURRENT value, indistinguishable from "no history covers this instant at
  all" (a task that has never moved), which is the ONLY case that still falls back to current.
  `fact_task_delivery.domain_key`/`epic_id`/`epic_domain_key`/`cross_domain` all move onto this
  as-was read. A sub-task still has no `task_epic` history of its own (D2, the documented gap), so
  it keeps the one-indirection CURRENT epic fallback `worklogRowsForItem` already used.
  `epic_domain_key` itself is always the epic's CURRENT domain (no epic-domain history table exists,
  unlike `task_domain`) — a known, deliberate limitation, not a bug.
- **Epic-logged worklogs get the epic's own domain, never `null` (A21, strengthening invariant 6).**
  `worklogRowsForItem`'s `taskDomainKey` used to read `null` for a worklog logged directly on an
  epic; it now reads the epic's own (current) domain, same as `epicDomainKey` — every
  `fact_worklog` row now carries a non-null `task_domain_key`. The SAME covering-row-vs-no-row
  distinction above applies to a TASK-logged worklog's own as-was domain/epic read here.
- **Foreign work (A21, A22).** `isForeignWork(authorTeamId, sprintTeamId, fallbackTeamId)`
  (`metrics/MetricsDeriver.kt`) is `author team != sprintTeamId` when the sprint team is known, else
  `author team != fallbackTeamId` — never `true` off an unknown team on either side. **A
  TASK-logged** worklog passes the task's own sprint team at `started_at` and the task's assignee's
  team at `started_at` (`assigneeAndTeamAt`, batch-scoped `ASSIGNEE` field intervals, V17's
  `fact_worklog.assignee_account_id_at_started`/`assignee_team_id_at_started`) as the fallback — the
  original A21 rule, unchanged. **An EPIC-logged** worklog (A22) instead passes `sprintTeamId = null`
  (epics carry no sprint at all) and the epic's OWN DOMAIN's resolved owner team
  (`context.ownerTeamByDomain[currentDomainKey]`) as the fallback — never the epic's assignee's team.
  `assignee_account_id_at_started`/`assignee_team_id_at_started` are still populated for EVERY
  worklog (epic-logged included) as informational bridge columns; only the `foreign_work`
  COMPARISON itself branches on `isEpic`.
- **Owner team (A19, A22) — storage and derivation, per DOMAIN not per project; the config API/UI
  is a later commit.** `MetricsDeriver.ownerTeamByDomain` (renamed from `ownerTeamByProject`)
  resolves each DOMAIN's (not project's — several project rows may share one `domainKey`) owner:
  1) every project row of the domain that carries a CONFIGURED owner
  (`metrics.domain_map.owner_team_id`, `MetricsConfigService.domainOwnerTeamIds`, filtered to
  currently ACTIVE teams first — A22, a soft-deleted team's mapping resolves as if unconfigured)
  must AGREE — rows with no configured owner are ignored when checking agreement (a single
  configured row among unconfigured ones still "agrees" trivially); a genuine DISAGREEMENT between
  two or more distinct configured owners resolves to NO owner outright (a future config-PUT
  validation will `400` this — here it is simply unset, never falling through to the board
  fallback); 2) absent any configured owner at all, the team of the SINGLE `board_team_map` board
  (also active-team-filtered) mapped across ALL of the domain's project keys (`norm.boards
  .project_key`) — no mapped board, or more than one distinct team among several boards across the
  domain's projects, resolves to no owner; 3) otherwise absent — the report-time `UNOWNED` bucket.
  `fact_epic_delivery.owner_team_id` (V17) and the NEW `metrics.dim_domain.owner_team_id` (V17,
  `.claude/docs/persistence.md` "The `metrics` schema — measure-contract corrections (V17)") are
  BOTH written from this SAME resolution, so a report can read either table and see the identical
  owner for a domain's epics. `owner_team_id` (on `domain_map`, the CONFIGURED input) has no writer
  of its own yet — `MetricsConfigService.replaceConfig`'s per-connection PUT carries whatever value
  is already stored forward across its own full-replace (the request/response DTO and the OpenAPI
  spec are untouched this commit), so an unrelated config PUT can never silently wipe an owner a
  later migration or admin tool sets. `activeTeamIds` (`MetricsDeriver.activeTeamIds`, the SAME
  `TeamService.Teams`/`active()` read `MetricsConfigService.referenceData` already runs for the
  metrics-config PUT's own validation) is read ONCE per DERIVE, inside the rebuild transaction —
  a separate transaction from the settings/config read that stamps the run's `config_revision`. A
  team soft-delete landing between the two bumps the revision, so the post-run revision check
  (`IngestWorker.onSucceeded`) enqueues a fresh DERIVE: the gap self-heals rather than persisting. A
  configured owner that is now soft-deleted resolves to NO owner and does not fall back to a board
  (A22 — the admin's explicit choice is never silently replaced).

Tests: `DeriveKernelsTest` (`activeWaitMs`'s cases — never started/not done, the basic
minus-blocked sum, blocked time outside any IN_PROGRESS stretch contributing nothing — NOT_STARTED
and UNMAPPED gaps, a reopen-spanning blocked interval subtracted only where it overlaps IN_PROGRESS,
and the defensive floor-at-zero backstop); `MetricsDerivationTest` (`DerivedStubFixture`-based
sweeps for active+wait=cycle and 0≤active≤cycle over every DONE task, `current_team_id` against an
independently re-derived D5-at-now read over the open bridge rows — with the SAME closed-sprint
exclusion applied to the re-derivation, `fact_worklog.task_domain_key` never null (plus a guard that
at least one epic-logged worklog actually exists in the fixture), `assignee_team_id_at_started` and
`foreign_work` against an independent re-derivation of BOTH the task-logged sprint-else-assignee
rule and the epic-logged owner-team rule, `fact_epic_delivery.owner_team_id` equalling the FLO
board's own configured team for FLO epics; plus dedicated clone tests: an as-was epic test whose
covering `task_epic` interval genuinely carries no epic at `done_at` (asserting `epic_id`,
`epic_domain_key` and `cross_domain` all read as "no epic", never the task's later-assigned CURRENT
epic), `current_team_id` falling back to the assignee once its current sprint is CLOSED,
`foreign_work` true for a task-logged worklog with differing author/assignee teams and no sprint
team known, and the four-case owner-resolution suite — a configured override beating the board
default, two boards on one domain disagreeing (no owner), a soft-deleted configured owner (no
owner, no board fallback available either), and `dim_domain`/`fact_epic_delivery` agreeing on the
same resolved owner).

## Not yet ported / not yet written

The daily aggregates (`agg_daily_wip`/`agg_daily_flow`, the DERIVE reprocess/perf checks) round out
commit 9; the report API and the SPA pages all arrive with their own commits and their own
sections here.
