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
    MD and items). A `null` `accountId`/`label` group is the unassigned-at-commitment bucket --
    never a stored sentinel, the `credit_team_id` convention.
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
- **Period.** `from`/`to` -> `fact_sprint.complete_at` in `[fromMs, toMs]`, team-scoped when
  narrowed. `lastSprints=N` -> each team's own last N sprints with a non-null `complete_at`, ordered
  descending, resolved in Kotlin (teams are few and admin-curated, the `ReportService.filters()`
  precedent) -- reported per team in `meta.resolvedSprints`. `sprintId` -> that ONE sprint; unknown
  (checked against `metrics.dim_sprint` across the connection scope, regardless of team mapping) is
  `400`; a real sprint with no team mapped still 400s only if it does not exist at all -- an
  existing-but-unmapped sprint simply returns `sprints: []`.
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

## Not yet built

Every remaining named report (plan section 7's table: throughput, sprint consistency, estimation
accuracy x2, estimate adjustments, cycle time, reported-time ratio, WIP, backlog, aging WIP,
blocked time, data quality, epic progress/EVM, cost matrix) lands in its own later commit and
grows this doc with its own `## Report N -- ...` section, following `.claude/docs/measures.md`'s
own per-measure contract for what each number means.
