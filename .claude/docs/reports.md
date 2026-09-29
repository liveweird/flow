# Reports API (v0.3.0 M4)

This doc grows commit by commit alongside the v0.3.0 reports-API work (plan section 7 "Reports API"). It
starts with the foundation commit 10a builds -- the shared filter parser, `Distribution`, the
`meta` block, `GET /api/v1/reports/filters`, and the access posture every later report endpoint
inherits. Velocity, throughput, sprint consistency and every other named report (plan section 7's table)
land as their own commits and grow this doc with their own sections.

## Access posture

Every `/api/v1/reports/*` operation is **any signed-in user, read-only, no audit event** (D12 --
`.claude/docs/domain-model.md`: "Every signed-in user sees every report at every level,
individuals included"). No `requireAdmin` guard anywhere in this package -- configuration and
data-source pages stay ADMIN-only, but a report itself never does. Since nothing here mutates
anything, there is no `audit(...)` call to add -- the observability doc's per-mutation rule simply
does not apply (`.claude/docs/observability.md`).

## The shared filter parser (`reports/ReportFilter.kt`)

Every report endpoint (once one lands) parses its query string through ONE function,
`Parameters.parseReportFilter(calendar, nowMs, defaultDomainView)`, into a `ReportFilter`. It does
**structural** validation only (ranges, mutual exclusion, ISO date syntax) -- id EXISTENCE (`teamId`,
`sprintId`, `connectionId` against the database) is each report's own service's job once it reads
the filter back, never this function's (the client-supplied-FK `400`-never-`404` idiom
`metrics/MetricsConfigService.kt` already uses throughout). This split is what keeps the parser a
pure, DB-free unit (`ReportFilterTest`) while still keeping every "is this id real" check where the
data actually lives.

- **Period -- exactly one of three, mutually exclusive (`400` otherwise):**
  - `from`/`to` -- ISO dates (`YYYY-MM-DD`), inclusive, in the configured zone
    (`metrics.settings.time_zone`). Default: the trailing 90 days ending today. `to` must not be
    before `from`; the span (`to - from`, inclusive of both ends) must not exceed 1100 days.
    Resolves to a half-open `[fromMs, toMs)` UTC-millis pair via `metrics/WorkingCalendar.kt`'s own
    `dayBoundsMs` -- the parser never reimplements day-boundary math itself.
  - `lastSprints` (1..52) -- each team's own last N CLOSED sprints, unioned at UNIT level
    (`.claude/docs/domain-model.md`: "sprint-relative periods ... are per team"). A report's own
    service resolves the actual sprint ids and reports them in `meta.resolvedSprints`.
  - `sprintId` -- one specific sprint's own period.
- **Level -- `teamId`/`accountId` (plan section 7):** no `teamId` -> `UNIT` (groups by team); `teamId`
  alone -> `TEAM` (groups by user); `teamId` AND `accountId` together -> `USER` (the one user).
  `accountId` without `teamId` is `400` -- a user-level read always needs to know within which
  team's roster it is being read. `teamId = 0` is the UNASSIGNED sentinel bucket, a real value, not
  "absent" -- never rejected here (a real check that a POSITIVE `teamId` names an actual team is a
  report service's own job, downstream).
- **`domainView=TASK|EPIC`** (D3) -- the caller (each report's own route) supplies its own default
  (plan section 7: `EPIC` for PV/EV/AC-shaped measures -- backlog, worklog cost, epic accuracy; `TASK`
  elsewhere); an explicit `domainView` param overrides it.
- **Optional filters** -- `domain`, `activityType`, `workCategory` (the literal `UNCATEGORIZED` is a
  legal value, not just a real category name), `connectionId` -- all structural pass-through here,
  validated against the connection(s) in scope by the report's own service.
- **`breakdown=NONE|DOMAIN|ACTIVITY_TYPE|WORK_CATEGORY`** -- replaces the org drill inside a
  report's own `groups` with a slice by this dimension instead.
- Every scalar param goes through the shared strict readers (`infra/paging/QueryParams.kt`, see
  `.claude/docs/list-endpoints.md`): a repeated key is `400` (the `singleValue` rule), an unknown
  enum value is `400` (listing the allowed set).

## `Distribution` (`reports/Distribution.kt`)

The shared shape every per-item measure (cycle time, estimation accuracy, blocked time, ...)
returns: `{n, hidden, mean, min, max, p50, p90, p95, histogram[{from, to, count}]}`. `hidden = n <
minSampleSize` (`metrics.settings.min_sample_size`) -- when hidden, only `n` is populated, every
other field stays `null`/empty (`.claude/docs/domain-model.md` "Distributions": "Groups smaller
than the minimum sample size show counts only, with a note").

**Built in pure Kotlin over an already-fetched `List<Double>`, not a SQL fragment -- commit 10a's
own choice, documented in the file itself.** The plan allows either a
`percentile_cont`/`width_bucket` SQL fragment over a whitelisted column, or a pure Kotlin fallback;
commit 10a builds the reports FOUNDATION only, with no report query yet to attach a SQL fragment
to (velocity/throughput/estimation/etc. land in later commits -- plan section 10). The Kotlin path also
means `Distribution`'s own math is testable with zero database (`DistributionTest`). It mirrors
PostgreSQL's exact conventions so a later SQL-backed report never disagrees with this one over the
same input: `p50`/`p90`/`p95` use `percentile_cont`'s own linear-interpolation-between-closest-ranks
method; the histogram uses `width_bucket`'s own equal-width-bucket boundary rule (`[from, to)`
except the LAST bucket, closed on both ends, so the maximum value always lands somewhere). A
later report whose dataset is too large to pull wholesale into the JVM can run the SQL aggregate
directly and feed its own numbers into this SAME `Distribution` shape -- the wire contract never
changes, only which side does the arithmetic.

## `meta` (`reports/ReportMeta.kt`)

Every report response (once one lands) carries a `meta` block beside its own body:
`{derivedAt, configRevision, from, to, level, domainView, resolvedSprints[{teamId, sprintIds}],
minSampleSize}`. `derivedAt` is the latest SUCCEEDED `metrics.derive_runs.finished_at` across the
connection(s) the report actually read (`null` before any connection has ever completed a DERIVE);
`configRevision` mirrors the shared `metrics.settings.config_revision` every DERIVE stamps its rows
with (invariant 12 -- `.claude/docs/domain-model.md`). `from`/`to` are populated only for a
`from`/`to`-selected period; a `lastSprints`/`sprintId` period instead describes itself entirely
through `resolvedSprints`. `ReportFilter.toMeta(...)` assembles the DTO from an already-resolved
filter plus the figures a report's own service computes -- pure, no DB access of its own.

## `GET /api/v1/reports/filters`

The reference data every report's own filter bar/validation reads BEFORE a report is even
requested -- any authenticated user, read-only, **no query parameters at all** (so it declares no
`400` in the spec: `.claude/docs/testing.md`'s "declared statuses" rule -- an endpoint's status list
is trimmed to what it can actually answer, and this one has nothing to reject).

```
ReportFilters {
  teams: [{ id, name, sprints: [{sprintId, name, state, startAt, completeAt}], members: [{accountId, displayName}] }]
  domains: [{ domainKey, domainName }]
  activityTypes: [string]
  workCategories: [string]
  connections: [{ id, name }]
  derivedAt: epoch millis | null
  configRevision: integer
  minSampleSize: integer
}
```

- **`teams`** -- every ACTIVE team (`teams/TeamService.Teams`, cross-feature read), with the
  sprints its board has EVER produced (`metrics.dim_sprint` -- empty until at least one DERIVE has
  run for a connection whose board maps to that team) and its CURRENT (as of the request) D1 Jira
  roster, paired with a display name (`norm.people`, cross-feature read -- the FIRST place
  `/jira-memberships`' bare `accountId` gets a name attached, the same role `/jira-users` already
  plays).
- **`domains`/`activityTypes`/`workCategories`** -- the values a real DERIVE run has ACTUALLY
  produced (`metrics.dim_domain`/`dim_task`/`dim_epic`), never every value a per-connection
  config COULD map to: an admin's configured-but-unused mapping would otherwise clutter a filter
  dropdown with a value no report can ever return a row for.
- **`connections`** -- every ACTIVE connection (not additionally filtered to `enabled` -- a
  connection an admin has paused from syncing still owns whatever it already derived, and its
  history stays a legitimate filter choice), id and name ONLY -- never `settings`/the encrypted
  API token (`.claude/docs/security.md`).
- **`derivedAt`/`configRevision`/`minSampleSize`** -- the SAME figures every report's own `meta`
  block carries, so a client can label a still-warming-up connection ("no data derived yet")
  without a second round trip.

`reports/ReportService.kt`'s `filters()` runs three cross-feature reads inside its own
transaction, listed in `.claude/docs/persistence.md`'s cross-feature-read rule:
`teams/TeamService.Teams`, `norm/WorkItemStore.People`, `ingest/DataSourceService.Connections`.

## Wiring

`reports/ReportRoutes.kt`'s `configureReportRoutes()` is this package's composition root -- it
mirrors `metrics/Metrics.kt`'s own shape rather than `infra/db/Database.kt`'s: this package
composes services `configureDatabase`/`configureJira`/`configureMetrics` have ALREADY published
(`MetricsConfigServiceKey`, `TeamMembershipServiceKey`), so it constructs and publishes
`ReportService` right here, inside its own configure function, rather than in `Database.kt` itself
-- which runs BEFORE `configureMetrics` and so cannot see those keys yet. Registered in
`application.yaml` in the "-- features" group, after the metrics feature routes it reads from.

## Report 1 -- Velocity

`GET /api/v1/reports/velocity` (v0.3.0 M4 commit 10b, `.claude/docs/measures.md` "Report 1 --
Velocity"): `initial` = committed scope, `final` = final scope (A17) -- both read straight off
`metrics.fact_sprint` for the sprint-level rows, `metrics.fact_sprint_scope` for the per-user
breakdown. Takes the shared `from`/`to`/`lastSprints`/`sprintId`, `teamId`/`accountId` and
`connectionId` parameters (`domainView` is accepted and echoed in `meta` but never changes the
result -- velocity carries no domain slice, `measures.md`'s Report 1 rows: domain "--").

```
VelocityReport {
  meta: ReportMeta
  sprints: [{
    sprintId, name, teamId, completedAt,
    initialMd, initialItems, finalMd, finalItems,
    snapshot: { initialMd, initialItems, finalMd, finalItems } | null,
    drift: boolean
  }]
  groups: [{ teamId?, accountId?, label?, initialMd, initialItems, finalMd, finalItems }]
}
```

- **`sprints`** -- one row per (team, sprint) in scope. `snapshot` is the frozen
  `fact_sprint_snapshot` figures (`null` until the sprint was first seen closed and team-mapped by
  a DERIVE run -- D13); `drift` compares the two LIVE-vs-FROZEN figures with a 0.005 MD tolerance
  (item counts compared exactly) -- `true` whenever any of the four differs, always `false` when no
  snapshot exists yet.
- **Levels, from the parsed filter** (`reports/ReportFilter.kt`'s own `ReportLevel`):
  - **UNIT** (no `teamId`) -- every team's own CLOSED sprints in the period; `sprints` carries the
    whole-team figures (straight off `fact_sprint`); `groups` sums FINAL MD/items per team (`Σ
    final`, from the SAME `sprints` rows this response already carries -- no separate query).
  - **TEAM** (`teamId`) -- narrows `sprints` to that one team's own sprints; `groups` becomes a
    per-user split keyed by `fact_sprint_scope.assignee_at_commitment` -- committed
    (`committed AND in_scope_at_close`) sums `estimate_at_commitment_md`, final
    (`in_scope_at_close`) sums `estimate_at_close_md`, the SAME removed-row rule
    `DeriveKernels.sprintTotals` applies to the team total itself (`.claude/docs/metrics.md`
    "Sprint scope, facts and snapshots (D13)"), so `Σ groups == the team total` (both buckets, both
    MD and items; up to 0.01 MD per sprint of rounding when estimates have more than two decimals --
    see "Rounding" under Report 6). A `null` `accountId`/`label` group is the unassigned-at-commitment
    bucket, listed last -- never a stored sentinel, the `credit_team_id` convention.
  - **USER** (`teamId` AND `accountId`) -- `sprints` itself narrows to that ONE account's own
    contribution per sprint (the SAME committed/final predicates as the TEAM-level groups, applied
    per sprint instead of summed); `groups` is always empty -- there is nothing further to drill.
    `snapshot`/`drift` are always `null`/`false` at this level: a per-user FROZEN figure would need
    parsing `fact_sprint_snapshot.scope`'s JSONB (`.claude/docs/metrics.md`'s own documented "per-user
    velocity from the snapshot needs no child table" -- a reader this commit does not add), a
    deliberate, documented scope narrowing rather than a silent guess.
  - **`teamId = 0`** (UNASSIGNED) -- always empty (`sprints: []`, `groups: []`): a sprint always
    carries a real team or is excluded from this report entirely (an unmapped-board sprint shows
    only in report 14, per `measures.md`'s Report 1 rows).
- **Period.** `from`/`to` -> `fact_sprint.complete_at` in `[fromMs, toMs)`, team-scoped when
  narrowed. `lastSprints=N` -> each team's own last N sprints with a non-null `complete_at`, ordered
  descending, resolved in Kotlin (teams are few and admin-curated, the `ReportService.filters()`
  precedent) -- reported per team in `meta.resolvedSprints`. `sprintId` -> that ONE sprint; unknown
  (checked against `metrics.dim_sprint` across the connection scope, regardless of team mapping) is
  `400`; a real sprint with no team mapped still 400s only if it does not exist at all -- an
  existing-but-unmapped sprint simply returns `sprints: []`.
- **Open sprints.** An ACTIVE/future sprint has no `complete_at`, so only an explicit `sprintId`
  reaches it (`measures.md` conventions): `completedAt` is `null`, the figures are the live
  `fact_sprint` ones, `snapshot` is `null` and `drift` `false`. Pinned by `ReportVelocityTest`.
- **Connection scope.** `connectionId` narrows to one ACTIVE connection (`400` if unknown/inactive);
  absent, defaults to every ACTIVE (not soft-deleted) connection, the same set `/reports/filters`
  lists — a DISABLED connection only has syncing paused, and its derived data still counts.
- **`teamId`/`sprintId` existence.** A positive, non-zero `teamId` must name an ACTIVE team (`400`
  otherwise) -- the client-supplied-FK idiom, `reports/ReportFilter.kt`'s own doc comment: structural
  parsing stays in the parser, existence checks live in the service.

`reports/VelocityReport.kt` holds the wire DTOs, the drift comparison and every query/aggregation
function as an extension on `ReportService` (`reports/ReportService.kt`'s `database`/`metricsConfig`
are `internal`, not `private`, precisely so this sibling file can extend it without a second
constructor) -- the file the brief's own "past ~120 lines -> a new file" idiom names. Tests:
`ReportVelocityTest` (`DerivedStubFixture`-based: UNIT-level `finalMd` against `fact_sprint`, the
golden FLO sprint's `sprintId` period against `expected.json`'s `committedMd`/`finalMd`, TEAM-level
`Σ groups == team total`, `400` for `from > to` and an unknown `sprintId`; every request runs
through a non-admin `seededClient`, proving D12's `200` at the same time).

## Report 2 -- Throughput

`GET /api/v1/reports/throughput` (v0.3.0 M4 commit 10c, `.claude/docs/measures.md` "Report 2 --
Throughput", with the item counts of report 13): what was DELIVERED, in MD and items. Takes the
shared parameters plus `bucket=WEEK|MONTH` (default `WEEK`). Two views that **differ by design** --
a task finished after its sprint closed is in the period view but in no sprint's delivered scope,
and the sprint view prices at the sprint's close while the period view prices at `done_at`:

```
ThroughputReport {
  meta: ReportMeta
  bySprint: [{ sprintId, name, teamId, completedAt, deliveredMd, deliveredItems,
               snapshot: { deliveredMd, deliveredItems } | null, drift: boolean }]
  byBucket: [{ bucketStart, deliveredMd, deliveredItems }]
  groups:   [{ teamId?, accountId?, label?, deliveredMd, deliveredItems }]
}
```

- **`bySprint` (the SPRINT view)** -- straight off `fact_sprint.delivered_md/_items` (= Σ
  `fact_sprint_scope.done_in_sprint`), one row per (team, closed sprint) by `complete_at`, whole
  sprint or nothing. The period/`lastSprints`/`sprintId` resolution, connection scope and the `400`
  existence checks are velocity's own -- both reports share `reports/ReportSupport.kt`. `snapshot` is
  the frozen `fact_sprint_snapshot` delivered figures, `drift` the same 0.005 MD / exact-items
  comparison velocity uses. Sorted by `completedAt`, then `sprintId`. Carries no domain/activity/
  category slice (`measures.md`: domain "--").
- **`byBucket` + `groups` (the PERIOD view)** -- level-0 tasks (`fact_task_delivery.is_subtask =
  false`, D2) with `done_at` in the window, `deliveredMd` = Σ `COALESCE(estimate_at_done_md, 0)`,
  `deliveredItems` = count (an unestimated task is an item worth 0 MD). Team = `credit_team_id` (D5;
  `null` = UNASSIGNED, selected by `teamId=0`), user = `assignee_account_id_at_done`. Honours
  `domain` (TASK view, the default and what `meta.domainView` echoes: the task's own `domain_key`;
  EPIC view: `COALESCE(epic_domain_key, domain_key)`, A21 -- the fallback keys on
  `epic_domain_key IS NULL`, so it also covers an epic outside the ingested scope), `activityType` and `workCategory`
  (`UNCATEGORIZED` = no category -- so a real category literally named that cannot be selected).
- **Bucket rule.** `bucket=WEEK` weeks start Monday, `MONTH` starts on the 1st, both cut in the
  configured zone (`metrics.settings.time_zone`, never UTC -- a task done Monday 00:30 local belongs
  to that Monday's week even though it is still Sunday in UTC). `bucketStart` is the bucket's first
  day as an ISO date, so the first/last bucket may start outside the requested range (only tasks
  inside the range are counted). Empty buckets are zero-filled across the whole window, so a chart
  needs no gap handling. The math is the pure `bucketStart`/`bucketStarts` (`ThroughputBucketTest`).
- **Window rule.** A `from`/`to` period is exactly `[fromMs, toMs)` (exclusive end, as velocity). A
  `lastSprints`/`sprintId` period resolves the sprints (`meta.resolvedSprints`) and the period view
  reads their overall envelope `[min(start_at, else complete_at, else now), max(complete_at, else
  now)]` (inclusive end) -- an OPEN sprint (no `complete_at`, reachable only by an explicit `sprintId`;
  `bySprint` then carries its live figures, `completedAt` `null`, `snapshot` `null`) ends at the
  request's now, a sprint without `start_at` starts where it ends, and a not-yet-started future
  sprint gives an empty window. It is one window shared by every team, so at UNIT level a team whose sprints ended long before the
  latest one still sees the envelope, not its own narrower window. No resolved sprint means no window
  and an empty period view; `teamId=0` (UNASSIGNED) resolves no sprints at all, so with a
  sprint-relative period it yields nothing.
- **Levels.** UNIT: `groups` are one per credit team (a `teamId: null` group is UNASSIGNED, `label`
  the team name); TEAM (`teamId`): `bySprint` narrows to that team's sprints, the period view to tasks
  credited to it, and `groups` become one per assignee at done (`null` `accountId`/`label` =
  unassigned); USER (`teamId` + `accountId`): the period view narrows to that account, `groups` is
  empty and `bySprint` narrows to that account's deliveries by `assignee_at_commitment`
  (`done_in_sprint` rows at `estimate_at_done_md`; `snapshot`/`drift` are `null`/`false`, velocity's
  same narrowing). **Σ `groups` == Σ `byBucket`** (MD and items) at UNIT and TEAM level.
- **`breakdown`** is parsed by the shared filter but, as in velocity, does not change this report.
- **Two separate measures.** Never expect Σ `bySprint` == Σ `byBucket` (see the two-views note above).

Code: `reports/ThroughputReport.kt` (DTOs, pure bucket math, the queries) over `reports/
ReportSupport.kt` (the connection scope / team-existence / sprint-row / snapshot readers extracted
from velocity). Tests: `ReportThroughputTest` -- `DerivedStubFixture`-based: `bySprint` against `fact_sprint` and
`golden.sprint.deliveredMd/Items`; `byBucket` against an independent read of `fact_task_delivery` over
two windows; the sprint-envelope window for a `sprintId` and for `lastSprints=2`; an open/future
sprint by `sprintId`; Σ groups at UNIT/TEAM (and empty groups at USER); USER-level `bySprint` summed
over the golden sprint's assignees (plus the unassigned remainder) equalling the TEAM figure;
`teamId=0`; the `activityType` and TASK-view `domain` slices; WEEK vs MONTH totals; `400` for a bad
`bucket` and `from > to`. The EPIC domain view and the `workCategory` filters are pinned on
hand-built `fact_task_delivery` rows in a fresh disabled connection (the stub fixture has no work
categories and no cross-domain epics), with exactly hand-computed counts and MD. `ThroughputBucketTest`
pins the pure bucket math across Europe/Warsaw's DST change.

## Report 6 -- Sprint consistency

`GET /api/v1/reports/sprint-consistency` (v0.3.0 M4 commit 10d, reports 6.1-6.3 with the item counts of
13, `.claude/docs/measures.md` "Report 6"): how a sprint's scope moved and where it ended up --
committed, added, removed, final, delivered, carried over, dropped, MD beside items -- straight off
`metrics.fact_sprint` (+ `fact_sprint_snapshot`). Takes velocity's shared parameters (`from`/`to`,
`lastSprints`, `sprintId`, `teamId`/`accountId`, `connectionId`; `domainView` echoed, never changes
the result). Period resolution, connection scope, the `400` existence checks, `teamId = 0` (always
empty) and the open-sprint handling (`sprintId` only, `completedAt` null, live figures, no snapshot) are
velocity's own -- the shared `reports/ReportSupport.kt`, whose `SprintRow`/`SnapshotRow` now carry all
fourteen figures (`full`).

```
SprintConsistencyReport {
  meta: ReportMeta
  sprints: [{ sprintId, name, teamId, completedAt,
              committedMd, committedItems, addedMd, addedItems, removedMd, removedItems,
              finalMd, finalItems, deliveredMd, deliveredItems,
              carriedOverMd, carriedOverItems, droppedMd, droppedItems,
              snapshot: SprintFigures | null, drift: boolean }]      // SprintFigures = the same fourteen
  groups: [{ teamId?, accountId?, label?, ...the fourteen figures }]
}
```

- **The A17 partition** holds in every row and is what makes the report a consistency check: `final =
  delivered + carriedOver + dropped` (MD and items -- all four priced at the sprint's close), and
  `final = committed + added` in items (in MD only when nothing was re-estimated between an item's
  commitment/entry and the close); `removed` sits beside them, in no other bucket. `ReportSprintConsistencyTest`
  asserts it over every closed sprint of the fixture, so a `fact_sprint` regression fails there.
- **`snapshot`/`drift`.** `snapshot` is the frozen `fact_sprint_snapshot` (`null` until first seen closed
  and team-mapped, D13); `drift` is `true` when ANY of the fourteen live figures differs from it beyond
  0.005 MD (items exactly), `false` with no snapshot.
- **Levels.**
  - **UNIT** -- `sprints` are every team's sprints in scope; `groups` is one per team, summing every
    figure of that team's sprints (Σ groups == Σ sprints, all fourteen).
  - **TEAM** (`teamId`) -- `sprints` narrow to that team's; `groups` is one per
    `fact_sprint_scope.assignee_at_commitment` (a null `accountId`/`label` = unassigned at commitment).
    Each group's figures come from `DeriveKernels.sprintTotals` over that user's scope rows -- the SAME
    bucket predicates that produced the team's `fact_sprint` row (committed = committed ∧ in scope at
    close; added = `added_at` set; removed = `removed_at` set; final = in scope at close; delivered =
    `done_in_sprint`; carried over / dropped by their flags; each on the estimate column
    `sprintTotals` uses for it), never a re-implementation, so Σ groups == the team figures for every
    bucket (MD up to the rounding note below), and items exactly. A removed row is attributed to the user
    assigned at commitment, an added one to the user assigned at entry; the unassigned (null) group
    sorts last, as in velocity. The scope rows fetched are exactly the in-scope (connection, sprint)
    pairs -- never the cross product of the connection and sprint id lists, since two connections to
    one Jira site share sprint ids (velocity's per-user reader does the same).
  - **USER** (`teamId` AND `accountId`) -- `sprints` narrow to that account's rows per sprint (the same
    kernel over its rows; a sprint with none shows zeros); `snapshot` is `null` and `drift` `false`
    (velocity's documented narrowing: a per-user frozen figure would mean parsing the snapshot's
    JSONB scope), `groups` is empty. The unassigned bucket has no USER-level query -- it is the
    remainder Σ named users + unassigned == team.
- **Rounding.** `fact_sprint` stores `round2(Σ unrounded)` per sprint while the per-user groups are
  computed over `fact_sprint_scope` rows whose estimates are stored to two decimals, so Σ groups == team up
  to 0.01 MD per sprint of rounding when estimates have more than two decimals (both here and in
  velocity); items always match exactly. `BACKLOG.md` tracks rounding per item before summing in
  `sprintTotals`.
- **Not in this report.** Capacity and load (also under measures.md's Report 6 heading) are not part of
  this endpoint's shape; they stay on `dim_sprint`/`fact_sprint` for a later reader.

Code: `reports/SprintConsistencyReport.kt` (DTOs, drift, the query/aggregation functions as an extension
on `ReportService`). Tests: `ReportSprintConsistencyTest` -- the golden FLO sprint's fourteen figures
against `expected.json` exactly (plus its snapshot, no drift); the A17 partition over every closed sprint
(`lastSprints=52`, which covers every closed sprint only while a team has at most 52) and UNIT Σ groups; TEAM Σ groups == team for all fourteen figures; USER-level figures
per named account equal to that account's TEAM group, and Σ users + the unassigned remainder == team; a
hand-built case (the stub fixture has no removed scope and no estimated added scope: a fresh DISABLED
connection with hand-inserted `dim_sprint`/`fact_sprint`/`fact_sprint_scope` rows) asserting the per-user
removed/added figures exactly and that a second connection's stale scope rows never leak in; an
open sprint by `sprintId` (`completedAt` null, no snapshot); `teamId=0`; `400` for `from > to` and an
unknown `sprintId`. Every request runs through a non-admin `seededClient` (D12).

## Not yet built

Every remaining named report (plan section 7's table: estimation
accuracy x2, estimate adjustments, cycle time, reported-time ratio, WIP, backlog, aging WIP,
blocked time, data quality, epic progress/EVM, cost matrix) lands in its own later commit and
grows this doc with its own `## Report N -- ...` section, following `.claude/docs/measures.md`'s
own per-measure contract for what each number means.
