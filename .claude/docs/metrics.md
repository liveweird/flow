# Metrics layer (v0.3.0)

This doc is the operational reference for the v0.3.0 metrics layer (the model itself:
`.claude/docs/domain-model.md`): the ADMIN-curated inputs every DERIVE run reads under ONE
recorded revision, the derivation algorithm, the star schema mechanics, the daily aggregates and
the scale-20 performance figures. The per-number contracts are `.claude/docs/measures.md`; the
reports API that reads this layer is `.claude/docs/reports.md`.

## Configuration model

Two layers of configuration exist, both change-tracked through the SAME shared
`metrics.settings.config_revision` counter (`metrics/MetricsSettingsService.kt`; the per-connection config
lives in `MetricsConfigService.kt`, the editor options in `MetricsConfigOptions.kt`, owner resolution in
`DomainOwnerResolver.kt` — the checkup-D3 split of the former all-in-one config service):

- **Global settings** (`GET/PUT /api/v1/metrics-settings`, v0.3.0 M1 commit 3) — the ONE
  `metrics.settings` singleton: the calendar (time zone, weekend days, holidays), `hoursPerDay`,
  commitment grace, minimum sample size, the aging-WIP window/percentiles, the backlog-in-sprints
  window and the epic-drift threshold. See `MetricsSettingsResponse`/`Request` in
  `metrics/MetricsSettings.kt`. A PUT that changes anything bumps the revision through
  `MetricsSettingsService.bumpRevision`, which enqueues `DERIVE` for every enabled, active
  connection; an identical re-PUT bumps and enqueues nothing.
- **Per-connection configuration** (`GET/PUT /api/v1/data-sources/{id}/metrics-config`, v0.3.0 M1
  commit 4) — everything that is specific to one Jira connection's own data shape: status → stage,
  the estimate/epic-date/work-category field choices, project → domain, board → team, activity
  type and work-category maps, blocked statuses and per-sprint capacity overrides. This section
  covers that resource.

Both `metrics/TeamMembership*.kt`'s dated Jira-user team membership (D1, v0.3.0 M1 commit 3, its
own doc coverage in `.claude/docs/domain-model.md` "Configuration") and the per-connection resource
below bump the SAME shared revision — a DERIVE run always reads the
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
  domainStatusStages: [{ domainKey, statusId, stage }]  // metrics.status_stage_map, domain_key <> '' (per-domain overrides)
  fields: { estimateTask, estimateEpic, epicStart, epicDue, workCategory }  // metrics.field_config
  domains: [{ projectKey, domainKey, domainName, ownerTeamId? }]  // metrics.domain_map
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
| `domainStatusStages` | empty — no override exists until an admin makes one (see "Per-domain stage overrides") |
| `statusStages` | seeded from each status's Jira category (`norm.statuses`) — `new → NOT_STARTED`, `indeterminate → IN_PROGRESS`, `done → DONE`; a status whose category is `UNKNOWN` is left OUT of this list (flagged, never guessed — `.claude/docs/domain-model.md`'s "an unmapped status is flagged, never guessed"). The `metrics-config/options` endpoint's own `statuses` list still names every status Jira reports, mapped or not, so an admin can see the gap. |
| `fields.estimateTask` / `fields.estimateEpic` | the connection's stored data profile's `STORY_POINTS`-detected custom field id (`source_connections.profile.customFields`, `jira/JiraProfile.kt`) — both roles share the one detected field |
| `fields.epicStart` | the profile's custom field whose name contains "start date" (case-insensitive); if none, the field whose name contains "target start" (Jira Plans' own start-date field, for a company-managed-project tenant using Plans dates instead of a team-managed "Start date" custom field); `null` if neither was detected |
| `fields.epicDue` | `"duedate"` — Jira's plain system field, always a valid choice, never subject to profile detection |
| `fields.workCategory` | `null` — must be chosen; there is no sensible guess for which field carries it |
| `domains` | 1:1 — one row per project key observed in `norm.work_items` (LIVE rows only), `domainKey`/`domainName` both set to the project key itself; `ownerTeamId` is filled with the SAME resolved default `MetricsDeriver`'s own DERIVE run would compute — see "Domain owner team" below |
| `boards` | empty — no board → team mapping exists until an admin makes one |
| `activityTypes` | 1:1 — one row per issue type observed in `norm.work_items` (D6: activity types are standard issue types) |
| `workCategories` | empty |
| `blockedStatuses` | empty — only the Flagged field counts as "blocked" until an admin adds statuses here |
| `sprintCapacities` | empty — DERIVE (commit 8) falls back to a computed default (A3: members × working days) when no row exists for a sprint |

**Validation — the client-supplied-FK idiom, `400` never `404`** (there is no path id inside the
body): every id `DataSourceMetricsConfigRequest` carries is checked against exactly this
connection's own reference data (`metrics/DataSourceMetricsConfig.kt`'s `MetricsConfigReferenceData`,
computed by `MetricsConfigService.replaceConfig`/`.referenceData`; the editor's `options` read is `MetricsConfigOptions.options`):

- `statusStages[].statusId` and `blockedStatuses[]` → `norm.statuses` (`WorkItemStore.allStatusRefs`).
- `domainStatusStages[]` (`validateDomainStatusStages`) → `statusId` in `norm.statuses`; `domainKey` must be a domain the
  SAME request defines — a `domains[].domainKey`, or the project key of an observed project the request's `domains` leaves
  unmapped (DERIVE reads such a project as its own domain) — and never `''` (that is the every-domain `statusStages`
  row). The stage enum is enforced by deserialization; a repeated (domain, status) pair is the duplicate-key `400`.
- Every non-null field id (`fields.*`) → the stored data profile's `customFields` ids, plus the one
  hardcoded system field `"duedate"`.
- `domains[].projectKey` → distinct project keys observed in `norm.work_items`
  (`WorkItemStore.distinctProjectKeys`) — NOT the connection's own configured `projectKeys`
  setting, since the reference is "what Jira actually reports", not "what scope was requested".
- `domains[].ownerTeamId` (A19/A22, v0.3.0 M3 commit 9e — optional) → the ACTIVE teams registry,
  the `boards[].teamId` idiom (an unknown or soft-deleted team id is `400`); every `domains[]` row
  sharing the SAME `domainKey` must carry the SAME `ownerTeamId` (`null` or equal) — a disagreement
  is `400` too, checked in `validateDomains` (`metrics/DataSourceMetricsConfig.kt`, split out of
  `validateDataSourceMetricsConfig` to keep it under the repo's detekt complexity threshold).
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
  be finite, `0..999999.99` with at most two decimals (the `NUMERIC(8, 2)` column — Postgres would round the rest silently).
- **Column widths** (`validateColumnBounds`, run first; the over-long value would be a 500): `domains[].domainKey` and a
  `domainStatusStages[].domainKey` at most 50 characters, `domains[].domainName` and `workCategories[].category` at most 100 —
  the same constants (`DOMAIN_KEY_MAX_LENGTH`, ...) size the Exposed tables, and the strings are trimmed first
  (`sanitizedDataSourceMetricsConfig`, control characters a `400`). `activityType`/`valueId`/`valueName` are TEXT (V19), unbounded.

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

**Domain owner team (A19/A22, v0.3.0 M3 commit 9e).** `domains[].ownerTeamId` is now writable
through this API (it landed schema-only in V17, commit 9d — see `.claude/docs/persistence.md`'s
"measure-contract corrections"): a PUT stores it verbatim per project row, `replaceConfig` no
longer carries a prior value forward across its own full-replace (the request now owns it
outright). The agreement/board-fallback algorithm itself — "every project row of a domain that
carries a configured owner must agree; absent one, fall back to the domain's single mapped board's
team; otherwise no owner" — lives in exactly ONE place, `DomainOwnerResolver
.resolveOwnerTeamByDomain`, a pure function both `MetricsDeriver.ownerTeamByDomain` (the DERIVE
run, reading the EXPLICIT, DB-fresh `domain_map.owner_team_id` values via
`DomainOwnerResolver.domainOwnerTeamIds`) and this service's own `GET` (`MetricsConfigService.effectiveConfig` →
`DomainOwnerResolver.withResolvedOwners`) call — never duplicated. **`GET` always shows the resolved owner, not just
the raw stored value**: a `domains[]` row whose `ownerTeamId` is unset (`null`, whether the
connection is otherwise `configured` or the row is one of `defaultConfig`'s own computed rows)
is filled in with `resolveOwnerTeamByDomain`'s own board-fallback default — the SAME figure a
DERIVE run would resolve for that domain — so an admin always sees what will actually apply,
including on a connection where only `boards[]` has been configured and the owner itself never
explicitly set. An EXPLICITLY stored (non-null) value is never overwritten by this defaulting.

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
`MetricsConfigService.purgeConnectionConfig`, registered as a `PurgeStep` by
`metrics/MetricsJobHandlers.kt` and run by `ingest/IngestWorker.kt`'s `runJob` (through
`ingest/JobHandlers.kt`'s registry — `ingest/` never imports `metrics/`) right after the connector's
own `purgeSteps` finish. This is deliberately NOT wired into
`JiraConnector.purgeSteps`: the eight tables it drains hold no Jira-specific shape at all (a future
GitLab connector's connection would purge through the exact same call), so the cleanup lives beside
the OTHER connector-agnostic PURGE work the worker itself already owns, not duplicated per
connector kind.

### Per-domain stage overrides

`metrics.status_stage_map` is keyed `(connection_id, status_id, domain_key)`; `domain_key = ''` is the every-domain
mapping (`statusStages`), any other key a per-domain override (`domainStatusStages`, no migration — the column existed
since V15). A PUT replaces BOTH kinds together (one delete over the connection's rows, then one insert per list) and
bumps the shared revision like any other config change; an override-only change is a real change (bump, DERIVE
enqueue, `metrics_config.updated` audit), a re-PUT of the same overrides in any order is a no-op.

**Resolution (DERIVE, `DeriveContext.stageMapFor`):** for each item and status, the stage is the item's domain's
override row if one exists, else the `''` row, else `UNMAPPED` (flagged, never guessed). The item's domain is the
**current** one — `domain_map[item.projectKey]`, else the project key itself, the same read `dim_task`/`dim_epic`
`domain_key` carry — applied to the item's WHOLE status history, so a task that moved project is read through the
workflow of the domain it lives in now (a status interval's as-was domain is not tracked for stages). `buildContext` merges each overridden
domain's rows over the global map once per run (`ConfigMaps.stageMapByDomain`); a domain without overrides reads the global map itself,
so an unconfigured connection derives byte-for-byte as before. `MetricsStageOverrideTest` pins it: a hand-built
two-domain connection (override applies to its domain and status only; removing it restores the global stage) and
the stub, graded against an independent per-row resolution over the stored map.

**What an override does NOT change:** the Data quality report's unmapped-status finding keys on the every-domain rows only
(`reports/DataQualityConfig.kt` `readConnectionMappings`) — an override covers one domain's items, so a status with no
every-domain row stays listed (while ANY stage row, either kind, makes the connection "stored" rather than defaulted);
an item sitting in such a status in a domain that does override it still tiles to the override's stage.
`defaultConfig` never seeds overrides. The editor is the "Per-domain overrides" section under the Statuses tab
(`.claude/docs/web-features.md`).

## The DERIVE run algorithm (v0.3.0 M3 commit 7)

`metrics/MetricsDeriver.kt`'s `derive(context)` is the `DERIVE` job body
(`.claude/docs/ingestion.md` "The DERIVE job kind"): read the effective per-connection config +
global settings (one transaction, so a racing settings write can never let this run stamp a
revision newer than the config it actually derived under — review round 1), prune old
`derive_runs` rows (`MetricsStore.pruneDeriveRuns`, the `SyncJobsService.prune` shape, run on
`ingest.jobRetentionDays`, always keeping each connection's newest `SUCCEEDED` run — its DERIVE clock for the
snapshot reports), then — in ONE transaction — mark every OTHER `RUNNING` `derive_runs` row of this connection `FAILED` (`error_detail` "abandoned: worker lost its lease", `finished_at` = this run's clock: the job queue allows one DERIVE per connection, so such a row belongs to a worker SIGKILLed or lease-lost mid-DERIVE that can never finish it, and the prune only deletes terminal rows) and insert this run's own `RUNNING` row, then run the WHOLE rebuild inside
ONE `suspendTransaction`: ensure `dim_date` over `[min(created_at) − 1y, now + 2y]` (`MetricsStore.ensureDimDate` —
NOT part of that transaction, see "`dim_date` is global" below), delete every rebuildable `metrics.*` row for the
connection, insert `dim_domain` (small, config-derived, inserted once), then three ordered passes over the
connection's LIVE `norm.work_items` rows.

**Writes are multi-row `INSERT … VALUES`.** Every `MetricsStore.insertX` goes through `infra/db/MultiRowInsert.kt`'s `insertRows`
(the `batchInsert` call shape, one statement per chunk of at most 32,000 bind parameters; chunk sizes are quantized — the table's maximum or a power of two — so a table has only a handful of SQL texts, see `build-times.md` WHY 3): `exposed-r2dbc`'s own `batchInsert` executes every row
as a separate bound statement, ~0.16-0.19 ms a row however small, which was ~2.3 s of the ~3.3 s a stub DERIVE took (15,000 rows — WHY 3 in
`.claude/docs/build-times.md`); the stub DERIVE is now ~1.2 s (pass 1 0.12 s, pass 2 0.19 s, sprint 0.08 s, worklog 0.06 s, the WIP
`INSERT … SELECT` 0.5 s). The persisted rows are identical: a whole-derive A/B over all 20 `metrics.*` tables (WHY 3) is the evidence; `MultiRowInsertTest` pins the helper itself against `batchInsert` on three of the tables (plain, jsonb/nullable-reference and client-default columns).

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
(`MetricsDerivationTest`'s reprocess-digest test, `.claude/docs/test-fixtures.md`'s pattern).

**`ANALYZE`: after the commit when the previous statistics describe the connection's rows, in the transaction otherwise.** `MetricsStore.analyzeDerivedTables` runs one `ANALYZE` over the 16 tables the WIP/flow SQL reads (`ANALYZED_TABLES`: `dim_date`, `dim_domain`/`dim_task`/`dim_epic`/`dim_sprint`, the `task_*`/`item_estimate`/`item_stage` bridges, `fact_task_delivery`/`fact_sprint`/`fact_sprint_scope`/`fact_worklog`/`fact_epic_plan`). The rebuild is one transaction, so autovacuum can neither see the new rows nor run in time; tables whose statistics do not describe the connection's rows are planned at default `rows=1` estimates (nested loops — the build-times WHY 1 regression, 7.5 s → 12.3 s → 18.9 s per derive). `runDerivation` therefore decides, PER TABLE and like with like (`statisticsDescribeRows`), from the connection's newest SUCCEEDED `derive_runs.row_counts` (`MetricsStore.newestSucceededRunRowCounts`; that row is never pruned) and the counts it is about to write — `tasks`, `epics`, `sprints`, `worklogs`, `epicPlans` and `estimates` (`item_estimate`, counted in pass 1 and recorded in `row_counts` since this change): a key passes when the new count is 0 (nothing to plan) or the previous run wrote more than 0 and the new count is at most 2× it; a key the previous run did not record (an older build) fails, once. Config-dependent tables move independently of the task count — the admin's first config PUT that maps the estimate field fills `item_estimate` (the backlog flow joins it), mapping the sprint field fills the sprint tables — hence per key, not by task count. The statistics describe the rows only when a previous SUCCEEDED run exists and every key passes. Otherwise — the connection's first derive, one after a SUCCEEDED run over an empty table (a new connection derived before its first SYNC: any `bumpRevision` enqueues a DERIVE for every enabled connection), a config change that fills a table (above), or one over a more-than-doubled connection — the ANALYZE runs INSIDE the transaction, after `widenDimDate` and before the WIP step (`ANALYZE` is legal in a transaction block, unlike `VACUUM`, and counts the transaction's own inserts as live rows and its own deletes as dead), with no timeouts: it must see fresh statistics. **Every other derive ANALYZEs AFTER the commit and after `markRunSucceeded`**, as a top-level call in its own short transaction (`MetricsDeriver.analyzeAfterCommit`, before `context.heartbeat`): its WIP/flow statements plan on the previous committed state's statistics for the same connection's rows (same `connection_id` share, `issue_id` n_distinct, validity-range histograms; the planner rescales the row count by the actual block count), which was measured to give the same plans and the same 1.2-1.5 s derive as the in-transaction ANALYZE (build-times 2026-10-06 entry); the post-commit ANALYZE keeps them at most one derive old. That transaction starts with `SET LOCAL lock_timeout = 5000` (`DEFAULT_ANALYZE_LOCK_TIMEOUT_MS`, per table lock) and the transaction's `queryTimeout = 30` (`DEFAULT_ANALYZE_STATEMENT_TIMEOUT_MS` = 30 000 ms rounded UP to whole seconds, the whole statement — without it 16 tables could each wait 5 s; NOT a `SET LOCAL statement_timeout`, which Exposed's per-statement `SET statement_timeout` overwrites — `persistence.md` "Statement timeouts" — so for a long time this budget silently did not apply): a healthy ANALYZE takes 16-90 ms, so a table lock another session holds (manual VACUUM, another ANALYZE, DDL) occupies the worker SLOT for 30 s at most (plus the pool's own acquire timeout, 10 s by default and about 20 s with r2dbc-pool's one retry, `persistence.md` "Connection pool" — the query budget does not cover acquiring a pooled connection — so ~50 s worst case outside shutdown). That is slot occupancy, not lease safety — the claim's heartbeat is a concurrent ticker (`leaseSeconds/3`) that keeps running. A failure there, either timeout included, is a WARN, never a FAILED run (the data is committed and the run SUCCEEDED; the next derive or autovacuum refreshes the statistics); a re-derive's post-commit ANALYZE can WARN when it overlaps another connection's in-transaction one (that derive holds the locks to its commit). Deliberately not analyzed: `agg_daily_*` (written, not read), `team_membership` (config) and `norm.*` (PROCESS already committed those rows, so autovacuum analyzes them). The ANALYZE's `SHARE UPDATE EXCLUSIVE` locks conflict with themselves: the post-commit one is held for milliseconds, so **concurrent derives of different connections overlap** (measured: two re-derives take ~0.55 of their sequential time, was ~0.8 with the ANALYZE held to the commit); only an in-transaction derive still holds them to its commit, so two of those serialize from the ANALYZE to the commit — the second's ANALYZE waits for the first's commit (no deadlock — same tables, same order). The ANALYZE must not be made skippable (`SKIP_LOCKED` would leave a blind derive without statistics or silently age the others). The locks also conflict with VACUUM/autovacuum and DDL (autovacuum on these tables is skipped or cancelled meanwhile; an anti-wraparound vacuum would make the ANALYZE wait — rare; a post-commit wait is bounded by the timeouts and never touches the committed data). `MetricsAnalyzeTest` pins that a post-commit ANALYZE over a 1 s statement budget (the lock timeout set far out of reach, a foreign ANALYZE holding `item_stage`) is cancelled by the budget alone — a WARN carrying PostgreSQL's statement-timeout error and a SUCCEEDED run — and that a first and a second derive both advance `last_analyze` on every listed table, that a foreign transaction holding `ANALYZE metrics.item_stage` open blocks an in-transaction derive's commit (a first derive, a derive after a zero-task SUCCEEDED run) but not a re-derive's (whose post-commit ANALYZE waits and then runs, or times out with a WARN and a SUCCEEDED run), the per-table `statisticsDescribeRows` boundaries, that a config change filling `item_estimate` while the tasks stay flat takes the in-transaction path, and that the list covers every table the two steps' SQL mentions.

**Failure and cancellation.** A thrown exception (including a genuine coroutine
`CancellationException`, itself an `Exception` subtype) is caught once: `markRunFailed` stamps the
row `FAILED` with a truncated error detail, then the exception is rethrown so cancellation still
propagates correctly. `markRunFailed`'s own DB write runs under `withContext(NonCancellable)`
(review round 2b fix) — without it, a coroutine already cancelled by the time this catch runs would
never actually execute the `suspendTransaction` write, leaving the row stuck `RUNNING` forever. The
previously-written facts are untouched either way (the failed transaction rolled back before any
delete committed, or never started). The post-commit `ANALYZE` (see "`ANALYZE`: after the commit …") is deliberately NOT non-cancellable: a shutdown must be able to interrupt it so the worker's bounded join sees the claim released; `markRunFailed` only ever touches a `RUNNING` row, so a committed SUCCEEDED run is never flipped by a cancellation in that window. There is deliberately no `CANCELLED` status — see
`.claude/docs/persistence.md` "The `metrics` schema — the derived star (V16)".

### Reproducibility (invariant 12)

Every derived number is a pure function of `norm.*` plus ONE configuration revision, and
`MetricsDigestTest` proves it over the persisted rows (the reprocess-digest pattern,
`.claude/docs/test-fixtures.md`). `DerivedStubFixture.metricsDigest(connId)` MD5-hashes every derived
table — the dimensions, every bridge, both accumulating facts, the sprint/worklog/epic-plan facts,
`agg_daily_wip`/`agg_daily_flow` (plus, opt-in via `includeDimDate`, the `dim_date` days the
connection's WIP aggregate spans — `dim_date` is global and any DERIVE by ANY connection may rewrite a row (stamping
the then-current revision) after a calendar change, so only `MetricsDigestTest` hashes it) — in a deterministic order (the primary key, else every hashed column). Left out on purpose: `derive_runs`
(run bookkeeping), the surrogate `id` of the bridge/`fact_epic_plan` tables (a fresh
`autoIncrement()` per DERIVE) and `fact_sprint_snapshot.snapshot_at`/`reconstructed` (write-time
bookkeeping of an append-only row; the frozen figures themselves ARE hashed, though that slice is
trivially equal across re-derives — the live `fact_sprint` is what proves them reproducible). Three cases, each on a
private disabled clone under the pinned clock: (1) a second DERIVE over the same `norm` rows and
the same `config_revision` yields an identical digest and writes no second snapshot row; (2) a
REPROCESS (PROCESS rebuilding `norm.*` from the clone's raw rows) followed by a re-DERIVE
reproduces the digest of the first; (3) a negative sensitivity check — nudging one
`fact_task_delivery.blocked_working_days` by 0.0001, or deleting one `task_sprint` row (a
surrogate-id table), changes the digest, so the digest cannot be vacuous. Both derives share one `withMetricsSettings` wrapper — every
derived row carries the revision, and each wrapper call would bump it. A red digest is a real
nondeterminism bug (ordering, rounding, a clock read), never something to loosen.

## Calendar math (`metrics/WorkingCalendar.kt`)

Pure, timezone-aware: `dayOf(instant)` folds an epoch millis into an ISO date string in the
configured zone; `isWorkingDay(day)` checks the configured weekend-day set and holiday set;
`workingDaysBetween(a, b)` counts working days in `[a, b)`, additive
(`wd(a,b) + wd(b,c) = wd(a,c)`); `dimDateRows(from, to)` emits one `DimDateRow` per calendar day in
range, each carrying its own UTC-millis day boundaries and working-day flag — `metrics.dim_date`'s
own row shape (`.claude/docs/persistence.md`).

**`dim_date` is global, so it is ensured outside the DERIVE transaction.** Every connection's DERIVE needs the
same calendar rows, and a write inside a DERIVE's one big transaction would hold its row locks until that commit:
two DERIVEs with different ranges could deadlock (`40P01`) and, with `workerSlots=2`, would at best serialize for their whole run (only a derive whose statistics do not describe its rows still serializes from its `ANALYZE` to the commit, see "`ANALYZE`: after the commit …"; the deadlock is gone).
`MetricsStore.ensureDimDate(calendar, range, configRevision)` therefore runs in its OWN short
`inTopLevelSuspendTransaction` (a separate pooled connection that commits before returning, even when called from
inside the derive's `suspendTransaction`; the derive's later statements see the rows — `READ COMMITTED`, nothing in
the repo sets another level). `MetricsDeriver` calls it twice: with the initial range up front, and (`widenDimDate`)
with the full widened range once the facts exist; the two READS that decide the widened range
(`earliestFactEventMs`, `currentEpicPlanWindows`) stay in the derive's transaction. The transaction's first
statement is `pg_advisory_xact_lock(DIM_DATE_LOCK_KEY)` (one named constant, "FlowDate" as a `bigint`), so concurrent
ensures queue for milliseconds instead of taking row locks in different orders. It reads every stored row, takes
the span = the union of the stored `[min day, max day]` and the requested range (gaps between disjoint ranges are
filled), recomputes every day in it with the calendar and upserts ONLY rows that are missing or whose
`day_start_ms`/`day_end_ms`/`is_working_day` differ — so a time-zone, weekend or holiday change rewrites EVERY stored
row (not just the run's range: a stale row past the range used to keep the old zone's bounds and let range joins
double-match), and an unchanged calendar writes nothing (steady state: one full-table read of typically a few thousand rows, up to ~20k with the 50-year floor and epic windows, plus
the advisory lock, no row locks). `dim_date.config_revision` is the revision the row's CURRENT content was written
under, stamped on written rows only (it no longer advances on every DERIVE). No table references `dim_date(day)` by
foreign key, so the write needs no lock compatibility with the DERIVE's own inserts. **A stale caller only inserts:**
under the advisory lock `ensureDimDate` reads the CURRENT global revision (`metrics.settings.config_revision`); a caller
whose revision is lower (a DERIVE that started before a settings change) inserts MISSING rows only and never updates
one, so it cannot roll the table back under a newer run — including across an A→B→A change, where per-row stamps could
not tell. Only a caller at the current revision rewrites differing rows. Accepted edge case: a settings change landing
DURING a derive can leave that stale run itself reading the newer calendar's rows (or rows of two calendars) —
`bumpRevision` has already enqueued a re-DERIVE of every enabled connection, which corrects it. A stale run's inserted
days lie outside every current-revision run's span (inside it they already exist), and the next current-revision run
rewrites them if they differ, so the stale run does not corrupt anyone else's figures.
`DimDateContentionTest` pins the lock-free derive (a foreign transaction holding every row lock does not block it),
the whole-table rewrite, the zero-write steady state, the stale-caller-only-inserts rule (incl. A→B→A), that the ensure commits
separately from the caller's transaction, and the advisory-lock queueing.

## Kernel definitions (`metrics/Derive*Kernels.kt`)

Pure, per-item functions — no DB, the `norm/Tiling.kt` pattern — called once per issue by
`MetricsDeriver.kt`:

Plain top-level functions in four files, one per concern: `DeriveStageKernels.kt` (stage tiling, started/done,
blocked intervals, active/wait, epic drift), `DeriveTimelineKernels.kt` (estimate and project-key timelines, estimate
snapshots), `DeriveSprintKernels.kt` (sprint membership/scope/totals, `sumMd`) and `DeriveEpicPlanKernels.kt` (date
timelines, PV baselines/curve/horizon, `dimDateRange`); the tests stay in `DeriveKernelsTest`.

- **`stageIntervals`** tiles `norm` status intervals into `item_stage` rows via the configured
  `statusId -> ItemStage` map (the item's own domain's map — every-domain rows overlaid with that domain's overrides, "Per-domain stage overrides" above); an unmapped status becomes `ItemStage.UNMAPPED` (flagged, never
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

## Sprint scope, facts and snapshots (D13, v0.3.0 M3 commit 8)

`DeriveSprintStep.kt`'s sprint step (`runSprintStep`, `.claude/docs/domain-model.md` "Plan — PV"/
"Glossary") turns each level-0, non-sub-task task's Sprint-field history
(`sprintMembership`) into `metrics.fact_sprint_scope` rows (one per task × sprint it
was ever a member of), rolls those up into `metrics.fact_sprint` (one row per sprint), and freezes a
`metrics.fact_sprint_snapshot` row the first time a closed, team-mapped sprint is seen. Sub-tasks
and epics never carry independent sprint scope of their own (`sample-data/jira/generate.mjs`'s own
`sprintItemsOf`).

**Commitment, added, removed, final, delivered, carried-over, dropped** (`sprintScope`,
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
  `sprintTotals`'s own doc: "committed alone is not the committed-bucket predicate" —
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
task's own estimate timeline (`estimateAt`) at a FIXED instant per bucket — never the
task's current/latest value — so a later re-estimate never rewrites what a sprint's own commitment
or close figure already recorded.

`fact_sprint` (one row per sprint) is the Σ of its own `fact_sprint_scope` rows
(`sprintTotals`, invariant 8 — true BY CONSTRUCTION, since `MetricsDeriver` writes
exactly this function's output as the `fact_sprint` row; each item's MD is rounded half-up to two decimals
(`sumMd`) BEFORE the exact sum, matching the two-decimal value `fact_sprint_scope` stores per item, so
Σ scope rows and every per-user report group equal the team figure to the cent) plus `capacity_md`/`capacity_source`/`load`
(below); it is always the LIVE recomputation, rebuilt wholesale on every DERIVE.

### Default sprint capacity (A3)

`DeriveSprintStep.kt`'s `sprintCapacity` resolves `(capacity_md, capacity_source)` per sprint:

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

**Rounding, one-off.** A snapshot frozen BEFORE `sprintTotals` rounded each item before summing holds
`round2(Σ raw)`, while the live `fact_sprint` is now `Σ round2(item)` — up to n × 0.005 MD apart for a
sprint whose estimates carry more than two decimals (time-tracking estimates), so the drift flag can
show once for such a pre-upgrade snapshot. No deployment had snapshots when this landed; snapshots
written since are `Σ round2(item)` like the live row.

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

`DeriveWorklogStep.kt`'s worklog step (top-level `runWorklogStep`/`worklogRowsForItem`, moved outside
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
the live item set (`.claude/docs/test-fixtures.md`'s invariant-sweep pattern), rather than reading back
`fact_task_delivery.actual_md`'s own `decimal(10, 2)` column: that column's 2-decimal-place
rounding accumulates a real (if small) drift across ~1,200 issues, large enough to fail a naive
byte-for-byte comparison against `fact_worklog.md`'s finer `decimal(8, 4)` — the invariant is about
the underlying seconds never being double-counted or dropped, not about two differently-rounded
storage columns agreeing to the last decimal.

## Epic plans and PV (`fact_epic_plan`, v0.3.0 M3 commit 9b)

`DeriveEpicPlanStep.kt`'s epic plan step (top-level `runEpicPlanStep`, the sprint/worklog steps' own
`LargeClass` shape — moved outside the class, delegated to) is the LAST step of `runDerivation`,
after the worklog step: one `metrics.fact_epic_plan` row per BASELINE
(`EpicPlanBaseline`/`epicPlanBaselines`, `.claude/docs/domain-model.md` "Plan — PV",
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
- **The start/due date timelines** (`dateFieldTimeline`) mirror `estimateTimeline`'s
  own shape for a plain Jira date field (`YYYY-MM-DD`, parsed the `jira/JiraNormalizer.kt` `duedate`
  way — epoch millis at start of day UTC) — built from the connection's configured
  `fields.epicStart`/`fields.epicDue` field ids' own changelog history (`WorkItemStore
  .fieldChangesByFieldIds`, batch-scoped to epic issue ids only, the review round 2b memory bound
  applied here too) plus the CURRENT value (`DeriveContext.epicDateValue`, already used by
  `dim_epic`'s own `start_at`/`due_at` columns) as the timeline's own ground truth. The epic's own
  ESTIMATE timeline is never re-read here — pass 1's `ItemDerived.estimateTimeline` is reused
  verbatim, the same way `fact_epic_delivery`'s own snapshots read it.
- **PV curve (`pvCurve`, `WorkingCalendar`-driven, not persisted this commit — read by
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

- **Flow efficiency (A18).** `activeWaitMs` (pure, `metrics/DeriveStageKernels.kt`) sums
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
- **Current team and assignee (A21, A22).** `currentAttribution` (`metrics/DeriveTaskRows.kt`)
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
  (`metrics/DeriveTaskRows.kt`) is `author team != sprintTeamId` when the sprint team is known, else
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
- **Owner team (A19, A22) — storage and derivation, per DOMAIN not per project; the config API
  landed in commit 9e (see "Domain owner team" above).** `MetricsDeriver.ownerTeamByDomain`
  (renamed from `ownerTeamByProject`) resolves each DOMAIN's (not project's — several project rows
  may share one `domainKey`) owner via `DomainOwnerResolver.resolveOwnerTeamByDomain` (moved
  there in commit 9e so there is ONE implementation, shared with the config `GET`'s own owner
  defaulting):
  1) every project row of the domain that carries a CONFIGURED owner
  (`metrics.domain_map.owner_team_id`, `DomainOwnerResolver.domainOwnerTeamIds`, filtered to
  currently ACTIVE teams first — A22, a soft-deleted team's mapping resolves as if unconfigured)
  must AGREE — rows with no configured owner are ignored when checking agreement (a single
  configured row among unconfigured ones still "agrees" trivially); a genuine DISAGREEMENT between
  two or more distinct configured owners resolves to NO owner outright (the config PUT itself
  already `400`s a same-domain disagreement — see "Domain owner team" above — so this branch is
  reached only via a raw DB write bypassing the API, e.g. a future migration); 2) absent any
  configured owner at all, the team of the SINGLE `board_team_map` board
  (also active-team-filtered) mapped across ALL of the domain's project keys (`norm.boards
  .project_key`) — no mapped board, or more than one distinct team among several boards across the
  domain's projects, resolves to no owner; 3) otherwise absent — the report-time `UNOWNED` bucket.
  `fact_epic_delivery.owner_team_id` (V17) and the NEW `metrics.dim_domain.owner_team_id` (V17,
  `.claude/docs/persistence.md` "The `metrics` schema — measure-contract corrections (V17)") are
  BOTH written from this SAME resolution, so a report can read either table and see the identical
  owner for a domain's epics. `MetricsDeriver` deliberately reads `owner_team_id` fresh from the
  database (`domainOwnerTeamIds`) rather than trusting `DataSourceMetricsConfig.domains[]`'s own
  `ownerTeamId`, which the config `GET` may already have filled with a board-fallback DISPLAY
  default (see "Domain owner team" above) — keeping the DERIVE-time "what is explicitly
  configured" signal independent of the GET-time "what would apply" one.
  `activeTeamIds` (`MetricsDeriver.activeTeamIds`, the SAME
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

## Daily WIP aggregate (`agg_daily_wip`, v0.3.0 M3 commit 9f)

`DeriveWipStep.kt`'s `runWipStep` (`.claude/docs/domain-model.md` report 9, `.claude/docs/measures.md`
"Report 9 — WIP") is the LAST step of `runDerivation`, after the epic plan step: one
`metrics.agg_daily_wip` row per `(scope_kind, scope_id, day, item_kind, status_id, stage)`,
`item_count` the number of items whose `item_stage` interval covers the END of that day
(`valid_from < day_end_ms AND (valid_to IS NULL OR valid_to >= day_end_ms)`), for every calendar day
from the connection's earliest `created_at` day through the day of the run's own `now`.

**Raw SQL, not the batched Kotlin loop every other step uses.** A per-item-per-day WIP join over
`item_stage`/`dim_task`/`dim_epic`/`task_domain`/`task_epic`/`task_assignee`/`dim_sprint`/
`dim_domain`/`team_membership` — all freshly rebuilt earlier in the SAME run — is exactly the shape
the database, not the JVM, should compute for ~24k issues × years of days (plan §5). Four plain
`INSERT ... SELECT` statements (`MetricsStore.execAggDailyWip`, the `exec("SET LOCAL ...")` idiom
`MetricsStore.purgeAll` already uses, reused here for a plain statement instead) build the rows; only
NUMBERS (`connectionId`/`now`/`configRevision`) are interpolated into the SQL text, never a string.
`MetricsStore.deleteAggDailyWip` joins the OTHER per-run deletes at the top of `runDerivation` (the
`deleteFactEpicPlan` precedent); `MetricsStore.countAggDailyWip` reads the row count back for
`derive_runs.row_counts.aggWipRows` once every statement has run.

**Scope resolution, all AS-WAS at that day's own end instant** (`.claude/docs/domain-model.md`'s
"Stance" — everything effective-dated reads as-was, the WIP report's own anchor being a PAST day,
never "now"):

- **TEAM/TASK** — the sprint in the task's `norm` SPRINT field interval covering the instant
  (`norm.work_item_field_intervals`, field `SPRINT`, `value_id` the last sprint id — NOT
  `metrics.task_sprint`, whose carried-over rows overlap), mapped through `dim_sprint.team_id` and
  only while that sprint was not yet closed AT THE INSTANT (`complete_at IS NULL OR complete_at >
  instant` — no Jira `state` check here, unlike A22's NOW-evaluated `current_team_id`, since a past
  day's own instant already fixes what "closed as of then" means). Else the assignee's team
  (`metrics.task_assignee` covering the instant → `metrics.team_membership` covering it). Else
  `UNASSIGNED`. Historical teams are kept even if soft-deleted now — this is an as-was read, so A22's
  active-team filter (which applies only to NOW-evaluated columns) does not apply.
- **TEAM/EPIC** — `dim_domain.owner_team_id` of the epic's domain (as-is, A19), else `UNOWNED`.
- **DOMAIN/TASK** — the covering `metrics.task_domain` row's `domain_key`; a `LEFT JOIN` reads NULL
  alike for "no covering row" and "a covering row whose own value is null", so `COALESCE` folds
  either case onto the task's current `dim_task.domain_key` (in practice never null, since a
  domain-map miss already falls back to the project key itself at DERIVE time).
- **DOMAIN/EPIC** — the epic's own (current) domain, `dim_epic.domain_key`.
- **EPIC** — TASKS ONLY, via the covering `metrics.task_epic` row's `epic_id`; a task resolving to
  no epic (no covering row, or one whose own value is null) writes no EPIC-scope row at all — unlike
  DOMAIN, there is no "fall back to current" here (`.claude/docs/measures.md`'s own row text).

**Only non-zero counts get a row** — a plain `GROUP BY` already guarantees this (a group exists only
when at least one item landed in it), so no explicit zero-filter is needed anywhere in the SQL.
Sub-tasks are excluded throughout (D2): every scope's SQL joins `metrics.dim_task` filtered to
`is_subtask = false` BEFORE joining `item_stage`, so a sub-task's own `item_stage` row (pass 1
writes one for every item, sub-tasks included) never reaches any `agg_daily_wip` row.

Tests: `MetricsDerivationTest`'s three `agg_daily_wip -` cases — an independent per-stage TASK-count
oracle (`item_stage` + `dim_task`, end-of-day predicate) that TEAM (including `UNASSIGNED`) and
DOMAIN both sum to exactly, over evenly-spread sampled days; an independent per-team re-derivation
of the TEAM split on one sampled day, straight off the `norm` SPRINT/ASSIGNEE intervals and
`team_membership`; and a bundled check that no sub-task is ever counted, that a day predating the
connection's own history carries no rows at all, and that the LAST `agg_daily_wip` day equals the
day of `DerivedStubFixture.PINNED_NOW` in `Europe/Warsaw` (the derive run's own zone).
`DerivedStubFixture`'s own tripwire digest folds in `agg_daily_wip`
(`scope_kind, scope_id, day, item_kind, status_id, stage` order, its own natural key — no surrogate
id — the same rule every other bridge/fact's digest line already follows).

## Daily flow aggregate (`agg_daily_flow`, v0.3.0 M3 commit 9f)

`DeriveFlowStep.kt`'s `runFlowStep` (plan amendment A23) runs right after the WIP step, reading only
rows this same run already persisted: one SPARSE `metrics.agg_daily_flow` row per
`(scope_kind, scope_id, day)` (no row = zeros, nothing for idle days). **Storage:**
`throughput_items`/`throughput_md` hold the day's own INCREMENT (the report sums them into a curve
at query time — keeps the aggregate additive across scopes and days); `backlog_items`/`backlog_md`
hold the END-of-day snapshot. `pv_md`/`ev_md`/`ac_md` (part B, below) are increments too: the
planned/earned/spent MD landing on that day.

**Merge mechanism.** Every contribution is its own `INSERT ... SELECT ... ON CONFLICT (connection_id,
scope_kind, scope_id, day) DO UPDATE SET <col> = agg_daily_flow.<col> + EXCLUDED.<col>` statement
(`flowInsertHead`/`flowMergeTail` build the shared head and tail from the contributed column list), so
a backlog row and a throughput row for the same scope/day merge by addition in either order, and part
B's PV/EV/AC statements plug in the same way. Raw SQL through `MetricsStore.execAggDailyFlow`, only
numbers interpolated; `deleteAggDailyFlow` joins the per-run deletes, `countAggDailyFlow` feeds
`derive_runs.row_counts.aggFlowRows`.

**Day of an event** is the configured-zone day containing its timestamp (`dim_date.day_start_ms <= ts
< day_end_ms`).

- **Estimated backlog (D9)**, for every day of the WIP step's own range (`dayRangeCte`): a level-0
  task whose covering `item_stage` is NOT_STARTED, whose covering `item_estimate` is > 0
  (null/0 = unestimated) and that sits in no sprint with `start_at < day_end_ms`, i.e. started by the end of day d (covering
  `task_sprint` row; future or unstarted sprints still count as backlog, an active or closed one takes
  the task out). "Covering" is WIP's end-of-day rule. Scopes: DOMAIN = the as-was domain (covering
  `task_domain`, else `dim_task.domain_key`; the WIP `domainWipSql` rule), TEAM = that domain's
  `dim_domain.owner_team_id` (as-is) else `UNOWNED` (a task with no domain too), EPIC = the covering
  `task_epic` epic (none → no row). Set-based joins plus one `NOT EXISTS`. **The estimate is the
  task's OWN estimate only** (`item_estimate` holds no sub-task-summed timeline): a parent with no
  estimate of its own whose sub-tasks carry one (`estimate_source = SUBTASKS`) is NOT in the backlog,
  although throughput counts its summed `estimate_at_done_md` once done — a known asymmetry (A23,
  `BACKLOG.md` follow-up).
- **The merge is additive and non-idempotent by design**: every contribution statement adds into the
  PK row via `ON CONFLICT … DO UPDATE SET col = agg_daily_flow.col + EXCLUDED.col`, which is only
  correct because `runDerivation` deletes the connection's rows first and runs every step in ONE
  transaction.
- **Throughput, period view**, per day of `fact_task_delivery.done_at` (level-0 filter `is_subtask = false`;
  epics live in `fact_epic_delivery`): items = count, MD = `estimate_at_done_md` (an unestimated task is an item worth 0
  MD). Scopes: TEAM = `credit_team_id` else `UNASSIGNED`, DOMAIN = the task's own `domain_key` (D3
  flow view), EPIC = `epic_id` (null domain/epic → no row for that scope).

**PV / EV / AC (part B, report 15's storage; plan amendment A23).** Each is its own additive
statement (`pvTeamFlowSql`, `pvEpicDomainFlowSql`, `evTeamFlowSql`, `evEpicDomainFlowSql`,
`acFlowSql`); a zero total writes no row.

- **PV, TEAM (A20):** `fact_sprint.committed_md` on the day containing `dim_sprint.start_at`, for a
  sprint with a non-null `team_id` and `start_at`; scope_id = team id. Per team the rows total the
  committed MD of its started sprints.
- **PV, EPIC / DOMAIN:** every epic's CURRENT baseline (`fact_epic_plan.superseded_at IS NULL`, with
  start, due and budget set) spread over the WORKING days of `[start, due]` — `pvCurve`'s
  rule exactly: start/due read as UTC dates, the day key is that ISO date, a working day is the
  `dim_date` row with that key and `is_working_day`; no working day → no rows. The increments are
  cumulative-rounded (`round(budget*i/n, 2) - round(budget*(i-1)/n, 2)`), so they sum to the budget
  EXACTLY at scale 2 while the running sum stays within 0.005 of `pvCurve`'s cumulative. EPIC =
  the epic's issue id; DOMAIN = `dim_epic.domain_key` (the epic's own current domain, none → no row),
  summed from the same rounded increments, so it equals the sum of its EPIC rows exactly. Superseded
  baselines are not written — the report redraws them from `fact_epic_plan`.
- **PV horizon (A23).** An epic's current baseline gets a PV curve only if BOTH its start and due lie
  within `[now - 10 years, now + 10 years]` (UTC dates, `PV_HORIZON_YEARS`, `inPvHorizon`);
  outside it the epic is treated like "no dates" — never a clamped or partial curve, so Σ PV = budget
  stays true for every epic that has one. A placeholder date (9999-12-31, 1900-01-01) therefore cannot
  build millions of `dim_date`/`agg_daily_flow` rows on every run. The same filter drives both the PV
  SQL and the `dim_date` widening (`BACKLOG.md`: report 14 should flag such epics).
- **`dim_date` coverage.** Every flow-aggregate join on `dim_date` silently drops an event whose day
  has no row, so `MetricsDeriver.widenDimDate` (after the epic-plan step, before the WIP and flow
  steps) widens the table beyond the creation-based range to cover: the earliest of
  `fact_worklog.started_at`, `dim_sprint.start_at`, `fact_task_delivery.done_at` and item creation
  (one year of slack below, floored at 50 years before `now`), and every in-horizon epic window (one
  day of slack each side, up to its due date beyond the default `now + 2y`). The rule is the pure
  `dimDateRange` (unit-tested in `DeriveKernelsTest`); the write is `ensureDimDate` over the full
  range (only missing or changed rows are written). Events older than the 50-year floor are still dropped.
- **EV, TEAM (A20):** each `fact_sprint_scope` row with `done_in_sprint` in a team-mapped sprint counts
  `estimate_at_done_md` (null → 0) on the day of its task's `fact_task_delivery.done_at`; scope_id =
  the sprint's team. Per team the rows total `fact_sprint.delivered_md`. A task done inside two teams'
  overlapping sprints counts in EACH team's EV (consistent with each team's `delivered_md`, A20), so
  summing TEAM EV across teams can exceed throughput.
- **EV, EPIC / DOMAIN:** level-0 `fact_task_delivery` rows with `done_at` and an `epic_id`, at
  `COALESCE(estimate_at_done_md, 0)` on the done day; EPIC = `epic_id`, DOMAIN = `epic_domain_key`
  (null → no row). Epic-less tasks are left out (A23).
- **AC:** `fact_worklog.md` on the day of `started_at`. TEAM = `COALESCE(author_team_id, 'UNASSIGNED')`
  — never dropped, so Σ TEAM `ac_md` equals Σ `fact_worklog.md` (to numeric(10,2) rounding per row);
  EPIC = `epic_id` (an epic-logged worklog carries the epic's own id), DOMAIN = `epic_domain_key`
  where `epic_id` is set. Epic-less worklogs stay out of EPIC/DOMAIN (they remain in TEAM and in
  report 16's cost matrix). DOMAIN AC is summed from raw `md` then rounded once, so it can differ from
  the sum of its already-rounded EPIC rows by up to 0.005 per epic row.

Tests: `MetricsDerivationTest`'s `agg_daily_flow -` cases — throughput sums per scope plus a per-day
placement check; an independent bridge re-derivation of DOMAIN/TEAM/EPIC backlog on sampled days; the
invariant-9 sweep against `agg_daily_wip`; team PV/EV against `fact_sprint` (totals and per-day
placement); epic PV against `pvCurve` day by day (scopes = exactly the in-horizon epics); DOMAIN = Σ
EPIC plus the fact-derived oracle for EV/AC; team AC against `fact_worklog` (no worklog dropped).
`DeriveKernelsTest` pins `dimDateRange` and `inPvHorizon`. `DerivedStubFixture`'s digest folds the
table in (`scope_kind, scope_id, day` order).

## Performance (scale 20)

The v0.3.0 plan's DERIVE budget is **< 3 min on ≈ 24k issues**. Measured 2026-09-29 (v0.3.0 M5 commit 19b, branch `chore/m5-perf` on top of `14089b8` — the M5 head, every server report included; it re-runs M3's commit 9g check), on an Apple M5 Max (18 cores, 128 GB) running the stack in OrbStack (16 GB VM), through `docker-compose.perf.yaml` — its own compose project (`flow-perf`), its own volumes, ports 8184/5535/8194/8128, sized like production (app: `JAVA_OPTS=-Xmx512m` in a 768 MiB container as in `k8s/worker-deployment.yaml`; postgres 1 GiB as in `k8s/postgres-deployment.yaml`; the WireMock stub with 1 GiB heap and no request journal). A fresh database, one enabled connection (FLO/PLT/GTM/OPS), the FLO board (id 1) mapped to one team, everything else the computed defaults — except `blockedStatuses` = Blocked + Waiting (status ids 10003/10004), set in the same PUT so the blocked-time report has something to measure (the 9g run left it empty).

**Reproduce:**

```
node sample-data/jira/generate.mjs --scale 20 --out /tmp/flow-jira-x20      # ~8 s, 240 MB, never committed
docker compose -p flow-perf -f docker-compose.yaml -f docker-compose.perf.yaml up --build -d
# admin@flow.local / changeme on http://localhost:8184 — POST /api/v1/teams, POST /api/v1/data-sources
# (siteUrl https://<any>.atlassian.net — every call is rerouted to the stub), POST .../sync-jobs {"kind":"SYNC"};
# once SYNC succeeds: GET .../metrics-config, PUT it back with boards:[{boardId:1,teamId:<team>}]
# (that PUT enqueues DERIVE); read the timings:
docker compose -p flow-perf exec postgres psql -U flow -d flow \
  -c "select id, status, finished_at - started_at as ms, row_counts from metrics.derive_runs"
docker compose -p flow-perf -f docker-compose.yaml -f docker-compose.perf.yaml down -v   # ONLY the perf project
```

(The metrics-config PUT body is the GET body minus `configured` (an unknown key is a `400`), with `boards` and `blockedStatuses` filled in. `{"kind":"DERIVE"}` on `.../sync-jobs` re-runs DERIVE on demand. `FLOW_PERF_STUB_DIR` points the stub at another `--out` directory. Access tokens live 15 min — log in again for a long poll. For per-statement times `ALTER SYSTEM SET log_min_duration_statement = 300` on the perf database and read `docker logs flow-perf-postgres`.)

| Measure | Value |
|---|---|
| Dataset | 24,000 issues (23,720 tasks + 280 epics), 105,703 raw changelog histories, 73,283 status intervals, 23,977 worklogs, 84 sprints, 4 projects; issues created 2025-09-01 … 2026-09-29 (394 calendar days — the generator's history is ~13 months, a real 24-month backfill has ~2x the days) |
| SYNC (REFERENCE → PROFILE, cold database) | **478 s** (7 min 58 s; 438 s at 9g); the follow-up RECONCILE took 6 s |
| **DERIVE, first run (cold JVM)** | **137.8 s** — the DERIVE the SYNC itself enqueues, before the board is mapped to a team (76 % of the 180 s budget) |
| DERIVE, board mapped to the team (app restarted first, so a fresh JVM) | 86.3 s |
| DERIVE, again on the same data (warm JVM) | **82.4 s** — 99.3 s at 9g; the difference is run-to-run noise, not a change in the code |
| `row_counts` | tasks 23,720; epics 280; sprints 84; worklogs 23,977; epicPlans 351; aggWipRows 302,325; aggFlowRows 43,254 |
| Database size after DERIVE | 466 MB (428 MB at 9g); app container 663 MiB of 768 MiB RSS right after the last DERIVE and 507 MiB after the report runs below (no OOM, `OOMKilled=false`), postgres 256 MiB |

**Re-measured 2026-10-07 at production sizing (branch `fix/derive-heap-retention`, on top of `3f51ab4` + the `insertRows` memory fix; same machine, same `docker-compose.perf.yaml` — `-Xmx512m` in 768 MiB, postgres 1 GiB — and the same 24,000-issue dataset; two connections for the overlap rows, each with its own team).** The 2026-09-29 table above predates the multi-row `INSERT` work (`build-times.md` WHY 3) and the `ANALYZE` step; on master `3f51ab4` that work made DERIVE die with `OutOfMemoryError: Java heap space` at 512m: Exposed kept every executed multi-row statement (~8 MB each) reachable until the one DERIVE transaction committed, so a single DERIVE held ≥ 725 MB live (a 3 GiB heap survived only by being large). `insertRows`/`upsertRows` now release each chunk's statement (`MultiRowInsert.kt`, cause and pin: `build-times.md` WHY 14).

| Measure (512m heap unless noted) | Value |
|---|---|
| SYNC (cold database) | **281.5 s** (286.9 s in the earlier diagnostic run at 3g; was 478 s on 2026-09-29); the second connection's SYNC into the now-populated database 312.5 s; RECONCILE 3.9 s |
| DERIVE, first run (the one the SYNC enqueues, cold JVM, board not yet mapped) | **38.0 s** (was 137.8 s; before the fix: OOM at 512m) |
| DERIVE, board mapped, fresh JVM | **50.1 s** (was 86.3 s) |
| DERIVE, again (warm JVM) | **52.2 s** (was 82.4 s; 45.5 s at 3g in the diagnostic run — the 3g heap saves GC, not work) |
| Peak live heap (GC log, live set after a Full GC) | 275 MB for the cold first DERIVE, 128-130 MB for the mapped and warm ones — 1.8x-4x headroom under 512 MB (before the fix: ≥ 725 MB, unbounded in the dataset) |
| Container RSS (app, 768 MiB limit) | 657 MiB during the first DERIVE, 667 MiB at the post-SYNC peak, 468 MiB after the warm one; `OOMKilled=false`, no restart |
| **Two connections at once** (`ingest.workerSlots` = 2, the default; both DERIVEs enqueued within 0.2 s) | **68.9 s / 69.3 s** against 46.7 s (the second connection alone, mapped) and 50.4-52.5 s (the first, warm), i.e. **1.3-1.5x** one derive for two derives, not 2x; 64.7 s / 64.9 s (= 1.42x of 45.5 s) at 3g. Fits at 512m: peak Full-GC live set 230 MB, no OOM; app RSS 617 MiB |
| Database size, two connections | 854 MB (869 MB at 3g) |

Report latency spot checks, same method as "Report latency" below (20 requests after 2 warm-ups, p50 / p95 ms), on this build at 512m with one connection: `aging-wip` UNIT 274 / 279, `data-quality` UNIT wide 131 / 144, `cycle-time` UNIT wide 108 / 117. The earlier 512m run (before the final fix build, same code paths) gave `aging-wip` UNIT 278 / 294 with one connection and 547 / 563 with two (it ages every open item of every connection, so it doubles), `data-quality` UNIT wide 118 / 127 and 208 / 235, `cycle-time` UNIT wide 118 / 137 and 224 / 258, and the deep dive for the FLO domain with the 3 latest sprints (426 tasks) 37 / 38 ms. **The deep dive's 500-task cap is exceeded at this scale by 4 latest sprints** (the request is refused, `reports.md` report 17); nothing else in that run is near its budget.

**Stale planner statistics were the dominant cost in the test suite (measured, not yet at production scale).** Before the `ANALYZE` step above, a DERIVE of the ~1,200-issue stub clone (12-13k `agg_daily_wip` rows) took 7.4 s cold, 12.4 s on the second derive of the same connection and 18.9 s on the third (dead tuples pile up; `last_autoanalyze` is empty for every `metrics` table), with the WIP step alone at 3.9 s → 8.2 s and the backlog flow statement at 1.1 s → 14.6 s (18.6 M buffer hits vs 18.6 k after `ANALYZE`); with it every derive is a steady 2.9 s (WIP ~0.5 s, flow ~0.09 s, the `ANALYZE` itself 16-90 ms), and the full server test suite fell from 712 s to 369 s. The production (scale 20) re-measure with this step is still to come, so the figures below are the pre-`ANALYZE` profile: the WIP team/task statement's ~9 µs per surviving row from its two correlated sub-selects is not a statistics effect, so how much of the 46.7 s it recovers at 24k issues is unknown until then.

**Where the DERIVE time goes** (M3's 9g profile of the then-second run — not re-profiled at 19b, the totals above give no reason to think the shape moved; from postgres' statement log — the whole run is ONE transaction, so there is no per-step timing in `derive_runs`): the WIP step's four `INSERT … SELECT`s take 46.7 s in total — **team/task 38.1 s**, team/epic 4.6 s, domain 3.2 s, epic 0.8 s — the flow step's three statements ~1 s (each 0.3-0.4 s), and the remaining ~51 s is the JVM side (passes 1-3, sprint, worklog and epic-plan steps, all statements under 300 ms, i.e. the batched read/compute/insert loops). `EXPLAIN (ANALYZE, BUFFERS)` of the team/task WIP body shows the shape the step's own header warns about: the day × `item_stage` join yields 5.3 M candidate (day, interval) pairs of which 4.48 M survive the task join, and both correlated `COALESCE` sub-selects (sprint → team, assignee → team) run once per surviving row (4.4-4.5 M executions each, ~73 M buffer hits). Cost is O(days × open items), so it scales linearly with the date range and the number of tasks: a 24-month history would cost roughly double the WIP step, still inside the budget; the first thing to do if it ever is not is to resolve the team per (task, interval) once instead of per (task, interval, day) — no migration needed.


### Report latency

Every `/api/v1/reports/*` endpoint against the same scale-20 database (last DERIVE done, system idle, the app's 768 MiB container as above), one signed-in ADMIN, sequential `fetch` calls from a node script on the host (localhost, so this is server time plus loopback, no network): **20 requests after 2 warm-ups per case**, p50 / p95 / max in **milliseconds**. UNIT = no `teamId`; TEAM = `teamId=1` (the FLO team, which owns every mapped sprint); *default* = no period parameter (the trailing 90 days), *wide* = `from=2025-09-01&to=2026-09-29`, the whole ~13-month history (a real 24-month backfill has ~2x that; the parser caps a period at 1100 days). `aging-wip` has no period (as of now; its response is capped at 500 items — it truncates, `itemsTruncated`).

| Endpoint | UNIT, default period | UNIT, wide period | TEAM, default period | TEAM, wide period |
|---|---|---|---|---|
| `filters` | 6 / 7 / 7 | n/a | n/a | n/a |
| `velocity` | 5 / 6 / 8 | 10 / 11 / 74 | 21 / 23 / 24 | 96 / 103 / 110 |
| `throughput` | 20 / 24 / 24 | 62 / 68 / 69 | 9 / 9 / 9 | 29 / 34 / 34 |
| `sprint-consistency` | 5 / 5 / 6 | 9 / 11 / 11 | 22 / 25 / 25 | 90 / 96 / 114 |
| `task-estimation-accuracy` | 18 / 20 / 21 | 82 / 88 / 89 | 9 / 10 / 12 | 32 / 35 / 35 |
| `epic-estimation-accuracy` | 4 / 6 / 7 | 7 / 8 / 8 | 3 / 4 / 4 | 5 / 7 / 7 |
| `estimate-adjustments` | 31 / 34 / 35 | 138 / 149 / 151 | 12 / 13 / 13 | 43 / 49 / 51 |
| `cycle-time` | 24 / 25 / 25 | 108 / 116 / 119 | 10 / 11 / 11 | 41 / 42 / 43 |
| `reported-time-ratio` | 21 / 23 / 23 | 104 / 108 / 109 | 10 / 11 / 11 | 39 / 41 / 42 |
| `wip` | 5 / 6 / 6 | 14 / 15 / 16 | 4 / 5 / 5 | 10 / 11 / 11 |
| `backlog` | 4 / 4 / 5 | 6 / 6 / 7 | 4 / 4 / 5 | 6 / 6 / 7 |
| `aging-wip` (as of now, no period) | 271 / 278 / 280 | (same) | 10 / 12 / 12 | (same) |
| `blocked-time` | 20 / 21 / 22 | 92 / 99 / 100 | 10 / 12 / 14 | 40 / 47 / 47 |
| `epic-progress` | 7 / 8 / 9 | 8 / 9 / 9 | 5 / 6 / 7 | 6 / 7 / 7 |
| `data-quality` | 53 / 56 / 57 | 140 / 149 / 150 | 26 / 30 / 76 | 40 / 50 / 51 |
| `cost-matrix` | 5 / 5 / 5 | 9 / 10 / 10 | 2 / 3 / 3 | 3 / 3 / 4 |

Extra cases, same method: `epic-progress` at EPIC level (`epicId=FLO-10`, wide) 6 / 7 / 8, at DOMAIN level (`domain=FLO`, wide) 10 / 14 / 14; the Home page carries no report calls today, but the calls an overview would make — `velocity?lastSprints=1` 4 / 5 / 5, `cycle-time` UNIT default 23 / 26 / 26, `aging-wip` and `data-quality` above — are all far under a tenth of a second.

**Conclusion:** the plan target (every report p95 < 1.5 s) is met with a wide margin — the slowest case anywhere is the UNIT `aging-wip` at **278 ms** p95 (it ages every open item at the request's clock before the 500-row cap), then the wide-period distribution reports (`estimate-adjustments`, `data-quality`, `cycle-time`, `reported-time-ratio`: 100-150 ms); everything else is under 110 ms. Nothing is over or near budget, so no statement was captured or tuned. These are warm-cache reads on an otherwise idle stack: concurrent users and a cold buffer cache are not measured here.

## Status

The metrics layer is complete for v0.3.0: the configuration, DERIVE, the star and its aggregates,
and the re-derive/REPROCESS check ("Reproducibility (invariant 12)" above) are all shipped, and the
scale-20 performance check is recorded above. The report API and pages that read it are documented
in `.claude/docs/reports.md`; what is deliberately not built yet is listed in `BACKLOG.md` (seeding
memberships from Jira's Team field, reading `hoursPerDay` from Jira's time-tracking configuration).

**Membership history is permanent (by design).** Deleting a team closes its members' open
memberships at that moment (so they can join another team from then on), but the history before
that stays — `metrics.team_membership`'s EXCLUDE is per Jira account across all teams, so a new
membership can never be backdated over a period the person already spent in another team, deleted
or not. To correct a mistaken membership, edit or delete that row; don't delete the team first.
