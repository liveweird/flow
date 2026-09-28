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

## Not yet ported / not yet written

The sprint scope/facts + D13 snapshots (commit 8), worklog facts/epic plans/daily aggregates
(commit 9), the report API and the SPA pages all arrive with their own commits and their own
sections here.
