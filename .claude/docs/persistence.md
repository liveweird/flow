### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in
`application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service
(host port **5435** — Lettuce, Toadie and Covenant may occupy 5432/5433/5434 on the same machine;
in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from
  `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived
  JDBC connection. Migrations are the single source of truth for schema; do not call
  `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments
  included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql`
  makes every long-lived database refuse to boot (a failure no fresh-container CI run can see).
  `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and
  a red pin means REVERT the edit (clarifications go into this doc), never update the pinned
  value.
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access.
  `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the
  services and publishes them into `Application.attributes` (`UserServiceKey`, `TeamServiceKey`,
  `TokenBlocklistServiceKey` today); each service itself lives next to the feature it serves
  (`users/UserService.kt`, `teams/TeamService.kt`, `auth/TokenBlocklistService.kt`). The Exposed
  table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries
  only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and
  `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently
  wrap them); one wrinkle inherited from Toadie: V1 creates `users.id` as `BIGSERIAL` (64-bit in
  SQL, 32-bit everywhere above it) while `teams` uses `SERIAL`/`INTEGER` — harmless at this scale,
  documented so nobody "fixes" one to match the other without a migration (an `INTEGER` FK column
  may reference the `BIGINT` id; Postgres compares them fine).

### Connection pool

- **Applies when:** touching `infra/db/Database.kt`'s connect call, the `postgres.pool.*`
  configuration, or reasoning about how many PostgreSQL backends one Flow instance can hold.
- **Requirement:** Exposed connects through ONE bounded `io.r2dbc:r2dbc-pool` `ConnectionPool`
  (ported from Lettuce) wrapping the plain PostgreSQL R2DBC factory — never a raw
  `r2dbc:postgresql://` connect, which opens one backend per `suspendTransaction` with nothing
  capping concurrency (measured in Lettuce, v3.16.1, against its compose stack: 120 parallel
  requests against one endpoint took ALL 100 backends of PostgreSQL's default `max_connections`
  taken, 6 × `500` "sorry, too many clients already" and 7 × `401` — the JWT validation's
  blocklist read failed and surfaced as an invalid token — fixed in Lettuce v3.16.2: a throwing
  blocklist lookup now answers the catch-all's 500, never 401, the same fix ported into Flow's
  `plugins/Security.kt` alongside this pool, see "JWT model" in `.claude/docs/security.md`).
  Bounds come from `application.yaml`'s `postgres.pool` block, each boot-validated (startup fails
  outside the range, the `security.lockout.*` idiom, via the shared `infra/config/`
  `requireConfigInt`/`requireConfigLong` helpers, also ported from Lettuce): `maxSize`
  (`POSTGRES_POOL_MAX_SIZE`, default 20, 1..1000), `initialSize` (`POSTGRES_POOL_INITIAL_SIZE`,
  default 2, 0..maxSize — the floor the pool fills up to on its FIRST acquire, not at
  construction: r2dbc-pool warms up lazily and `warmup()` is deliberately not called. Outside
  development mode, `configureBootstrap`'s seed-password check (`countActiveWithPasswordHash`)
  issues a query during boot, so the floor is open before the first request there; in development
  (the common local/test path) nothing acquires during boot unless `ADMIN_INITIAL_PASSWORD` is
  set — the encrypted-at-rest backfill (`encryptedAtRestServices()`) is the OTHER boot-time
  acquirer — `DataSourceService` since v0.2.0, whose backfill select runs on every boot — so the
  floor now opens during boot in every mode),
  `maxAcquireTimeSeconds` (`POSTGRES_POOL_MAX_ACQUIRE_SECONDS`, default 10,
  1..600) and `maxIdleTimeSeconds` (`POSTGRES_POOL_MAX_IDLE_SECONDS`, default 600, 1..86400). Every
  pooled connection carries `application_name = flow` (`postgres.pool.applicationName`, test-only
  override), so operators count this instance's backends with
  `SELECT count(*) FROM pg_stat_activity WHERE application_name = 'flow'`. Size it as
  `maxSize × replicas + 1` (Flyway's short-lived JDBC connection, `infra/db/Flyway.kt`) well under
  the server's `max_connections`. A caller that waits past the acquire deadline fails with the
  pool's timeout exception, which `plugins/ErrorHandling.kt`'s catch-all renders as a logged
  `500` — deliberately NOT a new declared status, since the OpenAPI conformance gate would need it
  on every operation. The pool is disposed on `ApplicationStopped`, so every `testApplication` the
  suite boots releases its connections. Exposed's
  `R2dbcDatabase.connect(connectionFactory, databaseConfig)` derives the dialect from
  `databaseConfig.connectionFactoryOptions` alone, so the parsed options (still
  `driver=postgresql`) are threaded into the config unchanged while traffic goes through the
  pool. The same config pins `defaultMaxAttempts = 1`: Exposed would otherwise retry ANY
  `R2dbcException` three times, and the pool's acquire timeout is one — a saturated pool would
  cost 3 × the acquire budget per request and re-enter the acquire queue each time; Flow's writes
  have no path relying on that retry. **Dependency alignment:** r2dbc-pool 1.0.2 declares
  reactor-pool 1.0.8 (built on reactor-core 3.5.20), but Flow resolves a newer reactor-core, so
  `server/build.gradle.kts` imports the Reactor BOM (`reactor-bom` in
  `gradle/libs.versions.toml`) as a platform — it pins reactor-core, reactor-pool and
  reactor-netty as one release train, and `:server:checkDependencyAlignment` fails when any
  reactor module resolves to a version other than the BOM's. Move the `reactor-bom` catalog line
  together with the `netty` pin (reactor-netty's Netty version tracks the BOM). Covered by
  `ConnectionPoolTest` (bounded concurrency, a saturated pool's acquire timeout, disposal on
  `ApplicationStopped` and on a later module's failed startup, and the four config range-check
  cases) and `checkDependencyAlignment`'s own Reactor guard.
- **Exception:** the pool runs r2dbc-pool's defaults for liveness (`ValidationDepth.LOCAL`, no
  `maxLifeTime`): a connection killed server-side between uses is handed out once and fails that
  request with a 500 — accepted until a deployment introduces an idle killer or proxy.

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries
go through R2DBC.

**Cross-feature table reads AND writes (the service-layer rule, inherited from Lettuce).** A
feature service MAY query — or, since v0.3.0 M1 commit 3's team-delete/membership fix, WRITE —
another feature's Exposed table objects directly when the operation must run **inside its own
transaction** (SQL joins, atomic snapshots, a single soft-delete that must also close a dependent
row elsewhere) — the transaction boundary must be explicit rather than relying on an unrelated
service to preserve it. Exposed reuses an enclosing transaction for nested `suspendTransaction`
calls on the same database. Route handlers never touch tables (services only). The reads/writes in
place:

- `TeamService` joins `UserService.Users` for the roster's display fields and the active-member
  counts, and checks member ids against active users inside the create/add transaction.
- `TeamService.delete` (v0.3.0 M1 commit 3) WRITES `metrics/TeamMembershipService.TeamMembership`
  and `metrics/MetricsConfigService.Settings` directly, in the SAME transaction as the team's own
  soft delete: it closes/removes the team's D1 Jira-user memberships and bumps the shared
  `config_revision` — see "The `metrics` schema — configuration (V15)" below for why (a deleted
  team must never strand an account behind an `EXCLUDE`-guarded open membership it can no longer
  end).
- `metrics/TeamMembershipService` (v0.3.0 M1 commit 3) queries `teams/TeamService.Teams` directly —
  `list` only requires the team to EXIST (read-before-guard: a soft-deleted team's history stays
  visible), `create` locks it ACTIVE for the whole transaction
  (`TeamService.Teams.lockActiveForUpdate`, race-free against a concurrent `TeamService.delete`
  trying to lock the SAME row) — and queries `norm/WorkItemStore.People` directly (an unknown Jira
  account id is `400` — the client-supplied-FK idiom `TeamService.requireActiveUsers` already uses
  for Flow user ids), all inside its own transactions.
- `metrics/MetricsConfigService.referenceData` (v0.3.0 M1 commit 4) reads `teams/TeamService.Teams`
  (active teams) to validate `boards[].teamId` on the metrics-config PUT, inside its own transaction.
- `metrics/MetricsDeriver.activeTeamIds` (v0.3.0 M3 commit 9d) reads `teams/TeamService.Teams`
  (active teams) once per DERIVE, inside the rebuild transaction — the A22 rule that now-evaluated
  team columns and the domain owner never name a soft-deleted team.
- `reports/ReportService.filters` (v0.3.0 M4 commit 10a, `GET /api/v1/reports/filters`) reads
  `teams/TeamService.Teams` (active teams), `norm/WorkItemStore.People` (display names for a
  team's current D1 Jira members) and `ingest/DataSourceService.Connections` (id+name for active
  connections — never `settings`/the encrypted API token) directly, all inside its own
  transaction — a read-only reference-data assembly, never a write.
- `reports/ReportSupport.kt` (v0.3.0 M4 commits 10b/10c/10d/12, shared by `reports/VelocityReport.kt`,
  `reports/ThroughputReport.kt`, `reports/SprintConsistencyReport.kt` and the estimation reports
  `reports/TaskAccuracyReport.kt`/`EpicAccuracyReport.kt`/`EstimateAdjustmentsReport.kt`) reads
  `ingest/DataSourceService.Connections` (the active-connection
  scope and the `connectionId` existence check), `teams/TeamService.Teams` (the `teamId` existence
  check and team names) and `norm/WorkItemStore.People` (assignee display names) directly, inside
  the calling report's own transaction — read-only.
- `reports/WipReport.kt` (v0.3.0 M5 commit 15, `GET /api/v1/reports/wip`) reads `norm/WorkItemStore.Statuses`
  (status names for `by=STATUS`), `norm/WorkItemStore.BoardColumns` (the mapped board's columns for `by=COLUMN`, read
  at query time so a board edit shows up without a re-derive) and `metrics/MetricsConfigService.BoardTeamMap` (which
  board a team owns) directly, inside its own transaction — read-only, and `metrics/MetricsStore.AggDailyWip` for the
  series itself. `reports/BacklogReport.kt` (`GET /api/v1/reports/backlog`) reads `metrics/MetricsStore.AggDailyFlow`
  (the backlog trend) and `metrics/MetricsStore.FactSprint` (the mean `delivered_md` behind the backlog in sprints)
  the same way. `reports/SnapshotSupport.kt`, shared by both, reads `metrics/MetricsStore.DeriveRuns` (the per-connection
  newest successful run, via SQL `max()`) for the last-derived-day cut-off.
- `reports/AgingWipReport.kt` and `reports/BlockedTimeReport.kt` (v0.3.0 M5 commit 15 part b, `GET /api/v1/reports/aging-wip`
  and `/blocked-time`) read `norm/WorkItemStore.WorkItems` (issue key and summary, via the shared `workItemLabels`) and
  `norm/WorkItemStore.People`/`teams/TeamService.Teams` (through `orgGroups`/`accountDisplayNames`) directly, plus the
  `metrics` tables `FactTaskDelivery`, `FactEpicDelivery`, `DimEpic` and `ItemBlocked` -- all read-only, inside the report's
  own transaction.
- `reports/EpicProgressReport.kt` (v0.3.0 M5 commit 15c, `GET /api/v1/reports/epic-progress`) reads the `metrics` tables
  `AggDailyFlow` (the per-day PV/EV/AC increments), `DimEpic`, `DimDomain`, `FactEpicPlan`, `FactEpicDelivery` (the budget
  fallback) and `FactWorklog` (the team foreign-work share), plus `teams/TeamService.Teams` (team names and the active-team
  list of the unit drill) -- all read-only, inside the report's own transaction.

List each new cross-feature read/write here as it lands — the list IS the permission.

### Schemas

Flow's PostgreSQL database is split across three schemas (v0.2.0 plan §0 A3, the main-session
amendment over the architect's original all-`public`-with-prefixes recommendation):

- **`public`** — the v0.1.0 foundation tables (`users`, `teams`, `team_members`, `revoked_tokens`,
  `user_disabled_features`, …) plus the connector-agnostic operational tables that describe *how*
  ingestion runs rather than the data it pulls: `source_connections` (V8), `sync_jobs` and
  `sync_cursors` (V9). Flyway's own history table also stays here — `flyway.schemas` is left at its
  default, so it never needs to know the other schemas exist.
- **`raw`** — every connector's raw store, one table set per connector, named with the connector's
  own prefix so two connectors never collide: `raw.jira_issues` and `raw.jira_entities` (V10, see
  below), joined by `raw.jira_changelogs`/`raw.jira_worklogs` (V11, "The Jira changelog/worklog raw
  store" below) and `raw.jira_reconcile_seen` (V12, "The Jira RECONCILE scratch table (V12)" below)
  — the RECONCILE stream's own scratch table. A future GitLab connector adds `raw.gitlab_*`
  alongside these rather than inventing a fourth schema.
- **`norm`** — the neutral, source-agnostic layer every connector normalizes into (`work_items`,
  `sprints`, `boards`, …) — landed at V13 (plan commit 8a, "The normalized layer (V13)" below).
- **`metrics`** — the metrics layer's own schema: configuration lands first (V15, v0.3.0 M1 commit
  3, "The `metrics` schema — configuration (V15)" below); the derived star (dimensions/bridges/
  facts/daily aggregates) lands at V16, v0.3.0 M3 commit 7 ("The `metrics` schema — the derived
  star (V16)" below).

**How Exposed addresses a schema-qualified table.** No Exposed `Schema` object and no
`search_path` override are involved: `JiraRawStore.kt`'s `Issues`/`Entities` table objects simply
pass the dotted, schema-qualified name straight to the `Table(...)` constructor — e.g. `object
Issues : Table("raw.jira_issues")` — and Exposed/R2DBC resolve it as-is, including a cross-schema
FK reference back to `public.source_connections` (`reference("connection_id",
DataSourceService.Connections)`). The plan's fallback (setting `search_path` on the pooled
connection, with fully-qualified SQL in `exec` blocks, if qualified names misbehaved) was not
needed. `CREATE SCHEMA IF NOT EXISTS raw`/`norm`/`metrics` runs in the first migration that needs
each schema (V10 for `raw`; V13 for `norm`; V15 for `metrics`) — never a standalone "create
schemas" migration.

### The Jira raw store (V10)

`raw.jira_issues` and `raw.jira_entities` (v0.2.0 plan §4/§7, `jira/JiraRawStore.kt`) are the FIRST
tables in the `raw` schema — the REFERENCE and ISSUES streams' target
(`jira/JiraReferenceStream.kt`, `jira/JiraIssuesStream.kt`).

- **`raw.jira_issues`** — PK `(connection_id, issue_id)` (the stable Jira numeric id; a project
  move only changes the `issue_key`/`project_id`/`project_key` columns, never the PK). Identity
  columns (`issue_key`, `project_id`, `project_key`, `issue_updated_at`) sit alongside `payload`
  (the canonicalized `search/jql` issue document, `infra/json/CanonicalJson.kt`) and its `sha256`.
  `changelog_synced_at`/`worklogs_synced_at` are populated by the CHANGELOGS/WORKLOGS streams (plan
  commit 7, A1, `jira/JiraChangelogStream.kt`'s `markChangelogSynced`,
  `jira/JiraWorklogStream.kt`'s `markWorklogsSynced`) as of V11. `needs_processing`/`processed_at`/
  `processed_hash`/`processing_version` drive the PROCESS step (plan commit 8a, landed —
  `jira/JiraProcessStream.kt`, see "The normalized layer (V13)" below): `markChangelogSynced`/
  `markWorklogsSynced` flag `needs_processing = true` on every issue they touch, and
  `JiraRawStore.issuesToProcess` claims the resulting backlog, ascending issue id, batches of 50.
  `deleted_at`/`moved_out_at` are tombstone columns the RECONCILE stream sets (V12,
  `jira/JiraReconcileStream.kt`); nothing in the REFERENCE/ISSUES/CHANGELOGS/WORKLOGS streams sets
  `moved_out_at` on its own — a key/project change during an ISSUES page is just an ordinary column
  update, not a distinct "move" code path. Three partial indexes back the streams' own claim scans:
  `idx_raw_jira_issues_needs_processing` (PROCESS, plan commit 8a),
  `idx_raw_jira_issues_stale_changelog` (CHANGELOGS, plan commit 7, V10,
  `changelog_synced_at IS NULL AND deleted_at IS NULL`, read by
  `JiraRawStore.staleChangelogIssueIds`) and `idx_raw_jira_issues_stale_worklogs` (WORKLOGS, plan
  commit 7, V10, A1's per-issue backfill scan, same shape, read by `JiraRawStore.staleWorklogIssueIds`).
- **`raw.jira_entities`** — PK `(connection_id, kind, entity_id)`, one row per REFERENCE-stream
  entity kind (`JiraEntityKind`: `FIELD`, `STATUS`, `STATUS_CATEGORY`, `PROJECT`,
  `PROJECT_STATUSES`, `ISSUE_TYPE`, `PRIORITY`, `RESOLUTION`, `ISSUE_LINK_TYPE`, `USER`, `BOARD`,
  `BOARD_CONFIGURATION`, `SPRINT` — no CHECK constraint, since the Kotlin enum is the whitelist and
  the column drives no SQL-level behavior, the `users.role`/`sync_jobs.status` idiom reserved for
  columns a CHECK usefully pins). `entity_id` is `VARCHAR`, not `BIGINT`, because Jira ids are
  numeric for most kinds but an opaque `accountId` string for `USER`. `idx_raw_jira_entities_last_seen`
  (`connection_id, kind, last_seen_at`) backs the REFERENCE stream's end-of-pass tombstone sweep.
- **sha256 change detection (both tables).** `JiraRawStore.upsertIssue`/`upsertEntity` canonicalize
  the incoming payload (`infra/json/CanonicalJson.kt`) and hash it; an unchanged hash on a
  non-tombstoned row bumps only `fetched_at`/`last_seen_at` (`RawUpsertOutcome.UNCHANGED`) — the
  payload/`changed_at` columns are untouched, so `changed_at` tracks genuine content changes only,
  never a re-fetch that happened to return the same document.
- **Tombstones and resurrection.** `raw.jira_entities.deleted_at` is set by
  `markEntitiesDeletedNotSeenSince` at the end of a REFERENCE pass, for every kind, over rows whose
  `last_seen_at` is older than the pass's own start time (`passStartedAt`) and not already deleted —
  entities genuinely absent from this pass, not ones simply not yet reached (a resumed pass's
  `passStartedAt` is preserved across restarts, so a partial pass never tombstones entities its own
  later steps haven't visited yet). A tombstoned entity or issue seen again on a later pass/page has
  its `deleted_at`/`moved_out_at` cleared by the same upsert path that would otherwise report
  `CHANGED` — reported instead as `RawUpsertOutcome.RESURRECTED`.
- **PURGE (plan §0 A2).** `JiraRawStore.purgeIssuesBatch`/`purgeEntitiesBatch` (500 rows per call,
  `JIRA_PURGE_BATCH_SIZE`) delete one connection's rows in batches; `JiraRawStore.purgeAll` (an
  extension function) drains all four `raw.jira_*` tables (issues/entities plus the V11
  changelogs/worklogs tables below) by looping each batch call until it deletes zero rows.
  `JiraConnector.purgeSteps` wires this as the PURGE job's one connector-owned cleanup step.

### The Jira changelog/worklog raw store (V11)

`raw.jira_changelogs` and `raw.jira_worklogs` (v0.2.0 plan §4/§7 V11, A1, `jira/JiraRawStore.kt`)
join `raw.jira_issues`/`raw.jira_entities` (V10) in the `raw` schema — the CHANGELOGS/WORKLOGS
streams' target (`jira/JiraChangelogStream.kt`, `jira/JiraWorklogStream.kt`).

- **`raw.jira_changelogs`** — PK `(connection_id, history_id)`, one row per Jira changelog history.
  **Append-only**: a history is immutable once Jira creates it, so there is no diff/tombstone rule
  here (unlike every other raw table) — `JiraRawStore.insertChangelog` is a plain `ON CONFLICT DO
  NOTHING` keyed by the PK, safe to re-run after a crash, and correct whether the SAME history
  reaches here via the bulkfetch batch path or the per-issue fallback path (both dedup identically).
  `payload` is the history object exactly as Jira returned it (bulkfetch's `changeHistories[]` entry
  or the per-issue fallback's `histories[]` entry — same shape either way), canonicalized. History
  ids are numeric and globally unique on a real Jira instance, but the PK still scopes to
  `connection_id` like every other raw table. `idx_raw_jira_changelogs_issue`
  (`connection_id, issue_id, created_at`) backs the normalization layer's future per-issue changelog
  replay (plan §8) and the pipeline test's own per-issue assertions — every history for one issue,
  oldest first.
- **`raw.jira_worklogs`** — PK `(connection_id, worklog_id)`, one row per Jira worklog —
  **in-scope issues only** (plan §0 A1): this table only ever gets a row the WORKLOGS stream has
  already checked against a known, non-tombstoned `raw.jira_issues` row for THIS connection
  (`JiraRawStore.knownInScopeIssueIds`) — everything else is dropped before any write. Same
  sha256 diff/tombstone rule as `raw.jira_issues`/`raw.jira_entities` (`JiraRawStore.upsertWorklog`/
  `tombstoneWorklog`): a worklog CAN be edited after creation (a time-spent correction), unlike a
  changelog history, so `changed_at` needs the same "moves only on genuine content change" rule the
  issue/entity tables use. `idx_raw_jira_worklogs_issue` (`connection_id, issue_id`) backs the
  WORKLOGS stream's own per-issue read path and the normalization layer's future per-issue worklog
  replay (plan §8).
- **PURGE (plan §0 A2, V11).** `JiraRawStore.purgeChangelogsBatch`/`purgeWorklogsBatch` (same
  `JIRA_PURGE_BATCH_SIZE` shape as V10's) join `JiraRawStore.purgeAll`'s drain loop, changelogs and
  worklogs FIRST (both reference `raw.jira_issues` by `issue_id` only, not a FK, so ordering is a
  convention, not a constraint requirement) before issues/entities.

### The Jira RECONCILE scratch table (V12)

`raw.jira_reconcile_seen` (v0.2.0 plan §4/§7/§12 item 7, `jira/JiraReconcileStream.kt`,
`JiraRawStore.insertReconcileSeen`/`issuesMissingFromSeen`/`seenButUnknownIds`/`clearReconcileSeen`)
is a scratch table, not a raw store proper: it holds every issue id the daily RECONCILE id-sweep
(`search/jql fields=id`) saw during ONE pass, so the pass's own anti-join step (see
`.claude/docs/ingestion.md` "RECONCILE stream") can diff it against `raw.jira_issues`.

- **PK `(connection_id, job_id, issue_id)`** — scoped to the `sync_jobs` row driving the pass, not
  just the connection: a resumed pass (same `job_id`, after a lease loss/reclaim) re-inserts
  idempotently (`ON CONFLICT DO NOTHING`) rather than duplicating, and a stale/interrupted pass's
  rows never collide with a LATER pass's own `job_id`.
- **`job_id` is a plain `INTEGER` column, deliberately NOT a foreign key to `sync_jobs.id`.** The
  table is cleared in full (`JiraRawStore.clearReconcileSeen`) once its pass's own anti-join step
  completes; an FK would let a lingering scratch row from an interrupted pass that never reached
  cleanup block that job row's own eventual hard-delete prune (`SyncJobsService.prune`, "The
  `sync_jobs` prune hard-delete exception" above) — the scratch table must never be able to hold a
  job row hostage.
- Drained by `clearReconcileSeen` per connection (not per job), so any earlier pass's leftover rows
  for the SAME connection are swept up alongside the current pass's own.

### The normalized layer (V13)

`norm.*` (v0.2.0 plan §0 A3/§4/§8, plan commit 8a, `norm/WorkItemStore.kt`) is the FIRST schema
outside `raw`/`public` — the connector-agnostic facts every connector's PROCESS step rebuilds a
work item's rows into, one issue at a time, keyed the same way `raw.jira_issues` is
`(connection_id, issue_id)`. `norm/Tiling.kt`/`norm/Normalization.kt` build the in-memory shape;
`jira/JiraNormalizer.kt` is the Jira-specific parser that feeds it; `jira/JiraProcessStream.kt` is
the PROCESS stream that drives the write. See `.claude/docs/ingestion.md` "Normalized layer" for
the tiling invariants and stream mechanics — this section is the schema/persistence side only.

- **`norm.work_items`** — PK `(connection_id, issue_id)`. Every column is the CURRENT snapshot
  only — history lives in the interval/change tables below, never here. `status_category` is one
  of `TODO|IN_PROGRESS|DONE|UNKNOWN` (Kotlin-enum-whitelisted, not CHECK-constrained — the
  `raw.jira_entities.kind` idiom). `anomalies` is a JSONB array of anomaly codes flagged while
  tiling this issue's status history — flagged only, never "fixed" (see ingestion.md). `deleted_at`/
  `moved_out_at` mirror `raw.jira_issues`' own tombstones. `processed_at`/`processing_version` are
  this row's own bookkeeping, separate from `raw.jira_issues.processed_at`/`processing_version` (the
  raw row's own processing pointer) — both move together, written in the SAME transaction
  (`WorkItemStore.replaceWorkItem` + `JiraRawStore.markProcessed`, inside
  `JiraProcessStream`'s `context.transaction { }`).
- **`norm.work_item_status_intervals`** — one row per tiled status interval, PK-less surrogate
  `id SERIAL`, unique on `(connection_id, issue_id, seq)`. The first interval (`seq = 1`) always
  starts at `work_items.created_at` with `source = 'CREATED'`; every later one is `'CHANGE'`.
  `to_at IS NULL` marks the one interval that is always open. `idx_norm_work_item_status_intervals_issue`
  (`connection_id, issue_id`) backs the per-issue replay this table's own REPLACE and the pipeline
  test's invariant sweep both read.
- **`norm.work_item_field_intervals`** — the same tiling shape for `ASSIGNEE`/`SPRINT`/`FLAGGED`
  (`TrackedField`, Kotlin-enum-whitelisted, not CHECK-constrained), unique on
  `(connection_id, issue_id, field, seq)`. SPRINT's `value_id` is the LAST sprint id of a
  (possibly multi-valued) carry-over set; `value_text` is the comma-joined sprint names Jira's own
  changelog `toString` already carries — never recomputed from ids.
  `idx_norm_work_item_field_intervals_issue` (`connection_id, issue_id, field`) backs the same kind
  of per-issue/per-field replay.
- **`norm.work_item_field_changes`** — every tracked changelog item kept VERBATIM (never tiled):
  status, assignee, Sprint, Flagged, Rank, priority, resolution, issuetype, project, Key and story
  points. This is the only normalized record for the fields with no interval table of their own
  (priority, resolution, issuetype, project, Key, story points, Rank). `idx_norm_work_item_field_changes_issue`
  (`connection_id, issue_id`) backs the per-issue read.
- **`norm.work_item_worklogs`** — PK `(connection_id, worklog_id)`, mirrored from
  `raw.jira_worklogs` (already in-scope-filtered, A1) — PROCESS's own copy, so a metrics query
  never has to join back into `raw`. `idx_norm_work_item_worklogs_issue` backs the per-issue read.
- **Reference rows** (`norm.statuses`, `norm.people`, `norm.boards`, `norm.board_columns`,
  `norm.sprints`) — rebuilt WHOLESALE per connection on every PROCESS run
  (`WorkItemStore.replaceStatuses`/`replacePeople`/`replaceBoards`/`replaceSprints`), never
  diffed/upserted row-by-row like `raw.jira_entities`, since PROCESS already reads the full current
  `raw.jira_entities` set every time it runs. `norm.board_columns.status_ids` is a JSONB array of
  status ids rather than a join table, since it is only ever read whole.

**Per-issue REPLACE semantics.** `WorkItemStore.replaceWorkItem` is one transaction per issue
(plan §8 step 5): delete `norm.work_item_status_intervals`/`_field_intervals`/`_field_changes`/
`_worklogs` for that `(connection_id, issue_id)`, insert the freshly tiled rows, then upsert
`norm.work_items` (insert if no existing row, update otherwise) — all inside the SAME transaction
`JiraProcessStream` also uses for `JiraRawStore.markProcessed`, so a crash mid-issue never leaves a
half-written normalized row or a raw row pointing at rows that were never written.

**`PROCESSING_VERSION`** (`norm/Normalization.kt`, currently `2` — bumped from `1` by V14, "The
normalized layer gaps (V14)" below) — bump it on ANY change to the tiling/write-shape rules.
`JiraRawStore.issuesToProcess` claims a raw issue whose `processing_version IS DISTINCT FROM` the
current constant (or is `NULL`, or `needs_processing` is flagged), so a version bump reprocesses
every issue automatically on the next PROCESS pass — proven by `NormalizationPipelineTest`'s "a
processing_version mismatch makes an issue eligible for the next PROCESS pass".

**PURGE of `norm.*` rows (plan §0 A2).** `WorkItemStore.purgeWorkItemsBatch`/
`purgeStatusIntervalsBatch`/`purgeFieldIntervalsBatch`/`purgeFieldChangesBatch`/
`purgeWorklogsBatch` (500 rows per call, `NORM_PURGE_BATCH_SIZE`, mirroring
`JIRA_PURGE_BATCH_SIZE`) delete one connection's rows in batches; the small reference tables are
cleared outright (`purgeReferenceRows`, no batching needed — they are already rebuilt wholesale).
`WorkItemStore.purgeAll` (an extension function) drains field changes, field intervals, status
intervals, worklogs, then work items, in that order, before clearing the reference tables.
`JiraConnector.purgeSteps` runs `JiraRawStore.purgeAll` (the `raw.*` tables) THEN
`WorkItemStore.purgeAll` (the `norm.*` tables) as its two connector-owned PURGE steps.

### The normalized layer gaps (V14)

`V14__norm_phase3_gaps.sql` (v0.3.0 M1 commit 2, `.claude/docs/domain-model.md` "Gaps in `norm`
today") is purely additive — no existing V1–V13 file changes, no data migration, every new column
is nullable except one — and pairs with `PROCESSING_VERSION` bumping `1` → `2`
(`norm/Normalization.kt`), so every already-processed issue reprocesses automatically on the next
PROCESS pass and backfills these columns without any migration-time `UPDATE`.

- `norm.work_items` gains `hierarchy_level INTEGER` (an epic is level 1, never "type name = Epic" —
  `jira/JiraNormalizer.kt` prefers the issue's OWN `fields.issuetype.hierarchyLevel` when a tenant
  returns it, falling back to the REFERENCE stream's `ISSUE_TYPE` entities
  (`issueTypeHierarchy`) only when the issue document itself is silent — the sample stub never
  includes it on the issue document, but a real tenant sometimes does), `due_at BIGINT` (the system
  `duedate` field's current value, epoch millis at start of day UTC) and
  `custom_fields JSONB NOT NULL DEFAULT '{}'` (every FILLED `customfield_*` current value,
  canonicalized via `infra/json/CanonicalJson.kt`, keyed by field id — deliberately unfiltered by
  WHICH field, so a future configurable field re-derives without a REPROCESS; filtered by whether
  it carries anything: a literal JSON `null` and an empty array/object/string are dropped, so "the
  key is present" reliably means "the value is filled". **Known storage cost**: Rank (`gh-lexo-rank`)
  and any ADF-shaped rich-text custom field land here verbatim, duplicating bytes `raw.jira_issues`
  already stores — accepted for now since the metrics layer needs no filtering logic at read time;
  revisit if a real tenant's row size becomes a problem).
- `norm.work_item_field_changes` gains `field_id VARCHAR(100)` — the changelog item's own
  `fieldId`, normalized to `field_id = 'parent'` for EVERY matched parent-move item regardless of
  which of the three spellings actually matched (a real, name-only `IssueParentAssociation` item
  carries no `fieldId` of its own — without this normalization it would be invisible to
  `WorkItemStore.fieldChangesByFieldIds(connectionId, listOf("parent"))`; `field` alone is a
  display name only, unreliable across a field rename anyway) — plus
  `idx_norm_work_item_field_changes_field (connection_id, field_id, issue_id)`, the metrics layer's
  per-field replay index. **`field_id IS NULL` only on a row written before the PROCESSING_VERSION
  2 reprocess** (a pre-V14 row `norm/Normalization.kt`'s version bump has not yet rewritten) — every
  row a version-2-or-later PROCESS pass writes always carries one. The tracked set itself widens in
  code, not schema: EVERY `customfield_*` item, `duedate`, and a parent move (see below) — no
  longer just the handful of explicitly-known field ids.
- `norm.work_item_worklogs` gains `created_at BIGINT`/`updated_at BIGINT` — `raw.jira_worklogs`
  already stores the full payload; only `started_at` was kept here until now. Report 14's
  late-logging measure needs when a worklog was actually entered/last edited, not just the time it
  claims to describe.
- `norm.sprints` gains `complete_at BIGINT` (`completeDate`) — the metrics layer keys sprint
  periods on completion, not `end_at`.
- No DDL for **PARENT tiling**: `TrackedField.PARENT` (`norm/Tiling.kt`) is a plain new enum value
  — `norm.work_item_field_intervals`/`_field_changes` already store `field` as a
  Kotlin-enum-whitelisted string (the `raw.jira_entities.kind` idiom), so no CHECK/migration is
  needed to add a fourth tracked field. PARENT tiles exactly like ASSIGNEE (`value_id` = the parent
  issue id, `value_text` its key, `null` = unparented) — see "Normalized layer" in
  `.claude/docs/ingestion.md` for the parent-change detection rule and its real-tenant spellings.

`MigrationChecksumTest` gains V14's pin.

### The `metrics` schema — configuration (V15)

`V15__create_metrics_config.sql` (v0.3.0 M1 commit 3, `.claude/docs/domain-model.md`
"Configuration") is the FIRST table set outside `public`/`raw`/`norm` — the metrics layer's own
schema. `CREATE SCHEMA IF NOT EXISTS metrics; CREATE EXTENSION IF NOT EXISTS btree_gist;` opens the
file (the V4 `unaccent` idiom: contrib, trusted since PG13, `CREATE` on the database suffices, no
superuser — see the one-line note in `.claude/docs/security.md`). `btree_gist` is needed because
`metrics.team_membership`'s exclusion constraint mixes an equality operator class (`account_id`, a
plain `VARCHAR`) with a range-overlap operator class (`int8range(valid_from, valid_to, '[)')`) in
ONE GiST index — `btree_gist` is what makes `=` available inside a GiST index at all.

- **`metrics.settings`** — the ONE global configuration singleton (`id = 1` CHECK, seeded by the
  migration with every column at its documented default), read/written by
  `metrics/MetricsConfigService.kt`. `time_zone` defaults to `'Europe/Warsaw'`, not UTC (main-session
  amendment A4 — the unit is Polish; an admin can change it). `hours_per_day` is a manual setting in
  v0.3.0 (A5) — reading Jira's own time-tracking configuration is deferred to `BACKLOG.md`.
  `config_revision` is the ONE revision the whole metrics layer is built against: bumped inside
  EVERY config mutation's own transaction — this row's own PUT, `metrics/TeamMembershipService.kt`'s
  create/update/delete (nested into the SAME transaction via `MetricsConfigService.bumpRevision`,
  since both live in the `metrics` package — not a cross-feature read), and, from a later commit on,
  every per-connection config PUT.
- **`metrics.status_stage_map`/`field_config`/`domain_map`/`board_team_map`/`team_sprint_capacity`/
  `activity_type_map`/`work_category_map`/`blocked_statuses`** — the per-connection configuration
  tables the v0.3.0 plan's commit 4 (`GET/PUT /api/v1/data-sources/{id}/metrics-config`) reads and
  writes; created here (priority item 1 of commit 3) so no later migration needs to add them.
  `board_team_map` carries `CONSTRAINT uq_metrics_board_team_map_team_id UNIQUE (team_id)` — D10's
  "one board maps to at most one team", named in `plugins/ErrorHandling.kt`'s
  `UNIQUE_CONSTRAINT_DETAILS` for a friendly `409` (a real consumer, and its own route validation,
  arrive with commit 4).
- **`metrics.team_membership`** (D1) — Flow-owned, effective-dated Jira-user team membership, global
  by `account_id` (Atlassian account ids span sites, so two connections to the same site share one).
  `CONSTRAINT excl_metrics_team_membership_overlap EXCLUDE USING gist (account_id WITH =,
  int8range(valid_from, valid_to, '[)') WITH &&)` is invariant 1 ("a user belongs to ≤1 team at any
  instant") enforced race-free AT THE DATABASE — never a check-then-insert TOCTOU in Kotlin.
  `valid_to IS NULL` is the open-ended (current) membership; adjacent half-open intervals
  (`[a,b)` + `[b,c)`) never overlap, so back-to-back memberships for the same account are accepted.
  A violation raises **SQLSTATE `23P01`** (exclusion violation), which
  `plugins/ErrorHandling.kt`'s `respondDbFailure` now maps to `409 "Overlapping team membership for
  this account"` alongside `23505` (unique violation) — the FIRST consumer of a second SQLSTATE on
  that central 409 path. `team_id INTEGER REFERENCES teams(id)` (not `metrics.team_membership`'s own
  schema) — the team registry stays in `public`. `metrics/TeamMembershipService.kt` is the reference
  service: `GET/POST /api/v1/teams/{id}/jira-memberships`, `PUT/DELETE …/{membershipId}` (any
  authenticated read, ADMIN write — `.claude/docs/authorization.md`).
- **`metrics.derive_runs`** — one row per DERIVE run (populated as of V16 below); created here so no
  later migration needs to add it. `job_id` is a plain column, deliberately NOT a foreign key to
  `sync_jobs.id` — the `raw.jira_reconcile_seen` rationale (above): a `derive_runs` row must never be
  able to hold a `sync_jobs` row hostage from its own hard-delete prune. `status` is `CHECK`-
  constrained to `RUNNING`/`SUCCEEDED`/`FAILED` — a run a cancelled coroutine interrupts is marked
  FAILED, not a fourth `CANCELLED` value (`.claude/docs/metrics.md` "The DERIVE run algorithm"
  documents this choice; adding one would need altering the CHECK in a new migration). Hard-deleted
  on the SAME retention window `sync_jobs` itself uses (`ingest.jobRetentionDays`,
  `MetricsStore.pruneDeriveRuns`, called once per DERIVE run) — the `sync_jobs` prune precedent
  applied to a table that otherwise grows forever for a connector deriving every few minutes.
  Each connection's NEWEST `SUCCEEDED` run is exempt however old: the snapshot reports read it as
  the connection's DERIVE clock, and without it a derived connection would read as never derived.

### The `metrics` schema — the derived star (V16)

`V16__create_metrics_star.sql` (v0.3.0 M3 commit 7, `.claude/docs/domain-model.md` "Analytical
model") adds `sync_jobs.kind`'s `DERIVE` value (a dropped-and-recreated CHECK constraint — the ONE
exception to "never edit an applied migration": this is a NEW file, not a retroactive edit of V9)
and every `metrics.*` star table: `dim_date`/`dim_domain`/`dim_task`/`dim_epic`/`dim_sprint`,
`task_epic`/`task_domain`/`task_assignee`/`task_sprint`/`item_estimate`/`item_stage`/`item_blocked`
(the effective-dated bridges), `fact_task_delivery`/`fact_epic_delivery` (accumulating snapshots),
`fact_sprint_scope`/`fact_sprint`/`fact_sprint_snapshot`/`fact_worklog`/`fact_epic_plan` and
`agg_daily_wip`/`agg_daily_flow` (their table objects land in this commit; commits 8/9 add the
writers). Interval storage mirrors `metrics.team_membership`'s own precedent (V15): half-open
`valid_from BIGINT NOT NULL, valid_to BIGINT NULL` pairs, no `tstzrange` (no r2dbc-postgresql codec
for it). Every table is `connection_id`-scoped and rebuilt WHOLESALE per DERIVE run — delete then
insert, this commit's `MetricsStore.kt` splits each pair into a `deleteX`/`insertX` method so
`MetricsDeriver.kt` can delete ONCE up front and insert BATCH BY BATCH (`.claude/docs/metrics.md`
"The DERIVE run algorithm") — EXCEPT `dim_date` (global, upserted `ON CONFLICT (day) DO UPDATE`)
and `fact_sprint_snapshot` (append-only, see below).

**The first trigger in this repo.** `fact_sprint_snapshot` is immutable once written (invariant 11,
"a `fact_sprint_snapshot` row never changes once written") — enforced not in application code but by
a `BEFORE UPDATE OR DELETE` trigger (`metrics.forbid_snapshot_change()`/
`trg_metrics_fact_sprint_snapshot_immutable`) that raises unless the session has
`SET LOCAL metrics.allow_snapshot_delete = 'on'`. This is the ONE sanctioned bypass: `MetricsStore
.purgeAll`'s PURGE step sets it before draining a deleted connection's snapshot rows; no other code
path may. A trigger rather than an application-level guard because the invariant must hold even
against a hand-run `UPDATE`/`DELETE` — the same reasoning the `metrics.team_membership` EXCLUDE
constraint (V15) applies to invariant 1.

**PURGE and the hard-delete exception.** `MetricsStore.purgeAll` (the PURGE job's generic,
connector-agnostic step, `.claude/docs/ingestion.md` "Job orders") drains every rebuildable
`metrics.*` row for a connection, dims first through facts, snapshot rows LAST (after the
`SET LOCAL` bypass) — and, as of review round 2b, `metrics.derive_runs` too: a purged connection's
run history has no reader left once its raw/norm/star rows are all gone, so it joins the drain
rather than lingering as orphaned bookkeeping (the `sync_jobs` hard-delete-on-terminal precedent,
"Soft delete (convention)" above, applied here since `derive_runs` has no OTHER retention window of
its own until a connection is actually deleted).

`MigrationChecksumTest` gains V15's pin.

### The `metrics` schema — measure-contract corrections (V17)

`V17__metrics_contract_columns.sql` (v0.3.0 M3 commit 9d, `.claude/docs/domain-model.md`
"Amendments" A18/A19/A21/A22, `.claude/docs/measures.md`) is purely additive — every new column is
nullable, no existing V1–V16 file changes, no data migration: the next DERIVE run backfills all of
them wholesale (there is no `PROCESSING_VERSION`-style gate here, since `metrics.*` rows are
already rebuilt WHOLESALE by every DERIVE run, unlike `norm.*`'s per-issue REPLACE).

- **`metrics.domain_map.owner_team_id INTEGER NULL REFERENCES teams(id)`** (A19) — a domain's
  project row may have an explicitly configured owner team, feeding `metrics/MetricsDeriver.kt`'s
  `ownerTeamByDomain` (renamed from `ownerTeamByProject` — it resolves per DOMAIN key, since several
  project rows may share one, not per project; see "Owner team" in `.claude/docs/metrics.md`'s
  "Derivation corrections" for the full agreement/fallback algorithm and the A22 soft-deleted-team
  exclusion) via `MetricsConfigService.resolveOwnerTeamByDomain` (the one shared implementation,
  v0.3.0 M3 commit 9e). This column landed nullable and unwritable through the API in V17/commit
  9d; commit 9e added `domains[].ownerTeamId` to the per-connection metrics-config request/response
  DTO and the OpenAPI spec (`.claude/docs/metrics.md` "Domain owner team") — `MetricsConfigService
  .replaceConfig` now writes it verbatim from the request on every full-replace PUT, validated
  (an unknown/soft-deleted team, or two project rows of the same domain disagreeing, are both
  `400`) rather than carried forward from the prior stored value.
- **`metrics.fact_task_delivery.current_team_id INTEGER NULL REFERENCES teams(id)`,
  `current_assignee_account_id VARCHAR(100) NULL`** (A21, A22) — D5 evaluated NOW rather than at
  `done_at`, for every task, done or not: the team of the sprint in the task's SPRINT field
  interval containing `now` (via `board_team_map`), else its assignee's team at this instant (the
  ASSIGNEE field interval containing `now`, then `team_membership`) — never the `task_sprint`
  bridge, which keeps every carried-over membership open. A22: a CLOSED
  current sprint (`complete_at <= now`, or Jira `state == "closed"`) never resolves a team here — the
  fallback runs straight to the assignee — and a currently soft-deleted team is skipped (a
  soft-deleted sprint team falls back to the assignee's team; a soft-deleted assignee team gives
  `null`) (never an as-was column's own concern — see
  `.claude/docs/metrics.md`). `idx_metrics_fact_task_delivery_current_team`
  (`connection_id, current_team_id`) is a PARTIAL index `WHERE done_at IS NULL` — the aging-WIP
  report's own read pattern (report 11) only ever needs this column for STILL-OPEN items.
- **`metrics.fact_worklog.assignee_account_id_at_started VARCHAR(100) NULL`,
  `assignee_team_id_at_started INTEGER NULL REFERENCES teams(id)`** (A21) — the task's assignee
  (and their team) at the worklog's own `started_at`, populated for EVERY worklog (task- or
  epic-logged alike) as an informational bridge pair. For a TASK-logged worklog it also doubles as
  the "assignments half" of foreign-work detection: `author team != assignee team at started_at`,
  the fallback `isForeignWork` (`metrics/DeriveTaskRows.kt`) applies when the task carries no sprint
  team at that same instant. An EPIC-logged worklog's `foreign_work` instead compares the author
  against the epic's own domain's OWNER team (A22, below) — these two columns are still filled for
  it, but no longer feed that comparison.
- **`metrics.fact_epic_delivery.owner_team_id INTEGER NULL REFERENCES teams(id)`** (A19) — an
  epic's own domain's owner team, resolved by the SAME `ownerTeamByDomain` call `dim_domain
  .owner_team_id` below reads from — stored so a future owner-scoped report (4/11/14) never has to
  re-resolve it at query time, and so an epic-logged worklog's `foreign_work` (A22) can compare
  against the SAME value a report reads back here.
- **`metrics.dim_domain.owner_team_id INTEGER NULL REFERENCES teams(id)`** (A19, A22) — the
  domain's OWN resolved owner team, written by every DERIVE run alongside the rest of `dim_domain`'s
  wholesale rebuild, from the SAME `ownerTeamByDomain` map `fact_epic_delivery.owner_team_id` reads
  — a report or the data-quality finding ("Domains without an owner team", report 14) reads this ONE
  column rather than re-deriving the agreement/fallback algorithm itself.

**`TeamService.delete` never touches `domain_map.owner_team_id`** — the same way it never touches
`board_team_map.team_id` (V15 above): both are soft-delete-safe by construction. A soft-deleted
team's row still exists, so the FK stays valid; nothing strands like `metrics.team_membership`'s
open interval does (there is no exclusion constraint here to violate) — instead, `ownerTeamByDomain`
filters a soft-deleted team OUT of its OWN resolution at DERIVE time (A22), and a future
metrics-config PUT (or an admin re-pointing the owner) is the normal way to move the CONFIGURED
value off a retired team once its config API lands. No cross-feature write was added for this
migration — the persistence.md cross-feature list above is unchanged.

`MigrationChecksumTest` gains V17's pin.

Current migrations are `V1`–`V17`:

- `V1__init` — the `users` table: `name` (≤50), `email` (≤254), `password_hash`, `role` with
  `CHECK ("role" IN ('ADMIN', 'USER'))` (single-column role storage; the wire shape stays a
  `roles` set, see `.claude/docs/authorization.md`), `password_changed_at` (epoch millis, 0 =
  never — retained as a timestamp; V7 credential revisions govern refresh acceptance),
  `marked_as_deleted`; plus the partial unique index `uq_users_email_active` over active rows.
- `V2__create_revoked_tokens` — the JWT blocklist for `/logout`: `jti` PK + `expires_at`, with an
  index on `expires_at` (the revoke path prunes expired rows opportunistically, so the table
  stays tiny).
- `V3__seed_admin` — the bootstrap administrator `admin@flow.local` / `changeme`, idempotent via
  `ON CONFLICT DO NOTHING`; production neutralizes it at startup (see "Default admin" in
  `.claude/docs/security.md`).
- `V4__enable_unaccent_extension` — Lettuce's unaccent migration, backing every
  `containsNormalized` substring filter (see `infra/db/Sql.kt`).
- `V5__user_disabled_features` — Lettuce's per-user feature flags (the DISABLED set — no row =
  enabled, so the empty table needs no backfill): `(user_id, feature)` PK, `ON DELETE CASCADE`, no
  CHECK on feature (the Kotlin `Feature` enum is the whitelist — the V1 role-CHECK is the
  deliberate exception, not the rule), plus the feature index behind the users-list
  `feature`/`featureEnabled` filter pair; and, in the same file, the MFA seed (Toadie's model):
  MFA joins the flags with an INVERTED default (opt-in) — every pre-existing user gets the `MFA`
  disabled row (`ON CONFLICT DO NOTHING`); `UserService.create` inserts the same row for every
  later user.
- `V6__create_teams` — flat teams (Lettuce's, minus `manager_id` — no management chain by
  design): `teams` (`name` ≤100, `description` ≤500 nullable, epoch-millis `created_at`/
  `updated_at`, soft-delete) with the partial unique index `uq_teams_name_active` over
  `LOWER(name)` active rows (a deleted team frees its name, no case twins), and `team_members` — a
  **hard-delete join** (`team_id` CASCADE, `user_id` CASCADE, composite PK, an index on `user_id`
  for the "my teams" filter): no history worth keeping, wholesale add/remove per row; a
  soft-deleted user keeps the row (reads join `users` and flag it `deleted`), a soft-deleted team
  keeps its roster for the record. `SERIAL`/`INTEGER` ids with a `BIGINT` FK to `users` (the V1
  wrinkle).
- `V7__user_credential_revision` — see "Credential revision" below.
- `V8__create_source_connections` — see "Data sources (V8)" below.
- `V9__create_sync_jobs` — see "Sync jobs and cursors (V9)" below.
- `V10__create_jira_raw_store` — see "The Jira raw store (V10)" above: `CREATE SCHEMA IF NOT EXISTS
  raw` plus `raw.jira_issues`/`raw.jira_entities`, the first tables outside `public`.
- `V11__create_jira_changelogs_worklogs` — see "The Jira changelog/worklog raw store (V11)" below:
  `raw.jira_changelogs`/`raw.jira_worklogs`, the CHANGELOGS/WORKLOGS streams' target.
- `V12__create_jira_reconcile_seen` — see "The Jira RECONCILE scratch table (V12)" above:
  `raw.jira_reconcile_seen`, the RECONCILE stream's scratch table. No FK on `job_id` (see above).
- `V13__create_norm_layer` — see "The normalized layer (V13)" above: `CREATE SCHEMA IF NOT EXISTS
  norm` plus `norm.work_items`/`_status_intervals`/`_field_intervals`/`_field_changes`/`_worklogs`
  and the rebuilt-wholesale reference tables (`statuses`, `people`, `boards`, `board_columns`,
  `sprints`) — the PROCESS step's write target, the first tables outside `public`/`raw`.
- `V14__norm_phase3_gaps` — see "The normalized layer gaps (V14)" above: additive columns on
  `norm.work_items`/`work_item_field_changes`/`work_item_worklogs`/`sprints`, paired with
  `PROCESSING_VERSION` bumping to `2`.
- `V15__create_metrics_config` — see "The `metrics` schema — configuration (V15)" above:
  `CREATE SCHEMA IF NOT EXISTS metrics` plus the `metrics.settings` singleton, every per-connection
  configuration table, `metrics.team_membership` (D1, the EXCLUDE overlap guard) and
  `metrics.derive_runs` — the first tables outside `public`/`raw`/`norm`.
- `V16__create_metrics_star` — see "The `metrics` schema — the derived star (V16)" above: adds
  `DERIVE` to `sync_jobs.kind`'s CHECK, every `metrics.*` dim/bridge/fact/agg table, and the first
  trigger in this repo (`fact_sprint_snapshot`'s immutability guard).
- `V17__metrics_contract_columns` — see "The `metrics` schema — measure-contract corrections (V17)"
  above: `domain_map.owner_team_id`, `fact_task_delivery.current_team_id`/
  `current_assignee_account_id`, `fact_worklog.assignee_account_id_at_started`/
  `assignee_team_id_at_started`, `fact_epic_delivery.owner_team_id`, `dim_domain.owner_team_id` —
  additive columns backing A18/A19/A21/A22's derivation corrections.

The `users`/`teams` tables follow Toadie's dialect (`SERIAL`/`INTEGER` ids, epoch-millis `BIGINT`
timestamps, `marked_as_deleted` + partial unique indexes over active rows) and its idioms: a
CHECK only where the value drives behavior (`users.role`), a Kotlin enum as the whitelist
otherwise.

### Soft delete (convention)

`users` and `teams` are **soft-deleted** — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace,
`team_members`, a pure membership join, and `metrics.team_membership` (V15, D1) — a pure DATED
join: the deletable action is "this interval was entered by mistake", not "this person left" (which
is already modeled by closing the interval — `validTo`), and a soft-delete flag would need a
partial `WHERE NOT marked_as_deleted` exclusion constraint instead of the simpler table-wide one,
for no benefit a removed row can always be re-added with corrected dates) hard-delete — a new
hard-delete table needs a documented justification, exactly like Lettuce's exceptions list.
`sync_jobs` (V9) is the one
non-join exception: `SyncJobsService.prune` hard-deletes terminal rows (`SUCCEEDED`/`FAILED`/
`CANCELLED`) older than `ingest.jobRetentionDays` (default 90), run opportunistically on every
`IngestWorker` scheduler tick. The table is pure operational history — it drives no soft-delete
semantics of its own (a job's PENDING/RUNNING lifetime is what matters, and cancellation already
covers "remove without physically removing" for anything still open) — and unbounded retention
would grow it forever for a connector that syncs every few minutes; a fixed retention window with a
documented default is the same shape as the JWT blocklist's opportunistic pruning of expired
`revoked_tokens` rows. To add soft-delete to a new entity, follow the established pattern
(reference implementation: `users/UserService.kt`):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit
   adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — declare the table `: UIntIdTable("…"), SoftDeletable` with
   `override val markedAsDeleted = bool("marked_as_deleted").default(false)`; the ONE `active()`
   predicate, `nowMillis()` and `lockActiveForUpdate` come from `infra/db/SoftDelete.kt` (no
   private copies).
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get
   `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row
   select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`,
   returning the affected-row `Int`; guard `update` mutations the same way. The route maps
   `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`)
   and delete stays idempotent in effect — `UserService.deleteGuarded` shows the shape, adding the
   last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the
   `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is
soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`:
`CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the
Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A
clash with an **active** row still raises `23505 → 409` (mapped centrally in
`plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend
`UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email`
(`uq_users_email_active`, `V1`) and the team name (`uq_teams_name_active`, `V6` — an expression
index over `LOWER(name)`).

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` —
the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column
substring filter MUST use it. Also `orVanished` (post-commit read-back guard → 500, for every
create that reads its row back).

### Credential revision (V7)

`users.credential_revision` is an internal nonnegative BIGINT generation, initially zero. Every
password update/reset and conditional bootstrap rotation increments it atomically in SQL with the
hash update. It is carried in signed refresh tokens and pending MFA challenges; refresh/MFA
acceptance compares it with the current user row. It is not exposed in user DTOs.
`password_changed_at` remains a timestamp, not an authorization boundary.

### Data sources (V8)

`source_connections` (v0.2.0 plan §3/§4) is a **generic connector registry**, in `public` (the
main-session PG-schemas amendment keeps operational tables — `source_connections`, and later
`sync_jobs`/`sync_cursors` — in `public`; connector-raw and normalized data get their own `raw`/
`norm` schemas starting at V10): `kind` (`JIRA_CLOUD` today, `CHECK`-constrained since it drives
behavior), typed common columns (schedule, sync status, `config_revision`) and a `settings` jsonb
holding the connector-specific shape (Jira's `siteUrl`/`email`/`projectKeys`/`authScheme`/
`cloudId`) — so a future GitLab connector reuses this table, its queue and its worker outright.
`secret` is the FieldCipher-encrypted scoped API token — the first `EncryptedAtRest` consumer (see
"Encryption at rest" in `.claude/docs/security.md`). Soft-deleted via `marked_as_deleted` with the
usual partial unique index (`uq_source_connections_name_active`, case-insensitive over active
rows). `backfill_from` is a plain `VARCHAR(10)` ISO-date string, not a SQL `DATE` column — the
value is only ever read/written whole and validated in Kotlin (`ingest/DataSource.kt`), so adding
an Exposed date-column dependency bought nothing. `ingest/DataSourceService.kt` is the reference
service for this shape; `ingest/DataSourceRoutes.kt` the ADMIN-only CRUD (see
`.claude/docs/authorization.md`).

**`infra/db/Jsonb.kt`** — a repo-local `jsonb` column type, because `exposed-r2dbc` 1.5.0 ships no
JSON column type of its own. It needs no reflection into the raw `io.r2dbc.spi.Statement`: Exposed
already ships a `JsonColumnMarker` interface plus a `PostgresSpecificTypeMapper` that specifically
recognizes any `IColumnType` implementing it (binding a null via `Statement.bindNull(index,
Json::class)`, a `String` via `Statement.bind(index, Json.of(value))` —
`io.r2dbc.postgresql.codec.Json`) — implementing the marker IS the whole binding. Values are the
caller's JSON text verbatim; PostgreSQL's own `jsonb` storage reformats regardless (whitespace, key
order), so "round-trips" means the same VALUES survive, not the same bytes. Covered by
`JsonbColumnTest` (insert/select/update, null, nested objects/arrays, through Exposed R2DBC against
Testcontainers PG).

**`source_connections.profile` (V8's `jsonb` column, populated as of plan commit 9).** `profile` was
declared nullable in the V8 migration alongside `settings` but stayed unwritten until the PROFILE
step (`jira/JiraProfileStream.kt`) landed; `profile_at` (nullable `BIGINT`) is its companion
timestamp. `DataSourceService.updateProfile` writes `DataProfileSections`
(`ingest/DataProfile.kt`) — canonicalized the SAME way `settings` is
(`infra/json/CanonicalJson.kt`'s `canonicalJson`) — and `readProfile` reads it back as
`StoredProfile{profileJson, profileAt}`; both null fields mean "never computed", not an empty
object. `ingest/DataProfileRoutes.kt`'s one call site decodes `profileJson` (falling back to an
all-default `DataProfileSections()` when null) and wraps it with `profileAt` into the wire
`DataProfile` response (`withComputedAt`) — see `.claude/docs/ingestion.md` "Data profile" for what
each section measures.

**`infra/json/CanonicalJson.kt`** — canonical JSON for anything stored through `Jsonb.kt`: object
keys sorted RECURSIVELY (arrays keep their own order — position is meaning), rendered via
kotlinx.serialization's compact `JsonElement.toString()`, plus a `sha256Hex` digest of the result.
Two payloads that differ ONLY in object key order canonicalize to byte-identical text, so their
digests agree — this is key-order independence, not general structural equality: canonicalization
does not normalize number spelling (`1`, `1.0` and `1e0` parse to the same numeric value but stay
distinct token text, so they canonicalize to different strings and hash differently). The Jira raw
store's per-payload hash (plan §4, V10) is the next consumer, over payloads Jira itself serializes
consistently. Covered by `CanonicalJsonTest` (key-order independence, recursive sorting, array
order preserved, numbers/strings/unicode stable and idempotent, stable `sha256Hex`).

### Sync jobs and cursors (V9)

`sync_jobs` and `sync_cursors` (v0.2.0 plan §4/§5/§9, `.claude/docs/ingestion.md` "Sync-job queue"
and "Sync cursors") stay in `public` alongside `source_connections` — operational state, not
connector-raw or normalized data.

`sync_jobs` (`ingest/SyncJobs.kt`'s `SyncJobsService`) is ONE table serving as both the job queue
and its own history for every connector kind: `kind` (`SYNC`/`RECONCILE`/`REPROCESS`/`PURGE`) and
`status` (`PENDING`/`RUNNING`/`SUCCEEDED`/`FAILED`/`CANCELLED`) are both `CHECK`-constrained
(they drive claim/behavior, the `users.role` idiom); `priority` (`0` manual, `10` scheduled),
`requested_by_user_id` (nullable — null for scheduler-enqueued jobs) and `config_revision` (the
connection's revision at enqueue time, compared again at claim) round out the job identity.
Timing/attempts (`requested_at`/`started_at`/`finished_at`/`attempt`/`max_attempts`) and the
lease/heartbeat quartet (`lease_owner`/`lease_until`/`heartbeat_at`/`cancel_requested_at`) are
plain nullable `BIGINT`/`VARCHAR` columns read and written only through the service, never a raw
SQL update. `uq_sync_jobs_open_per_kind` — a partial unique index over `(connection_id, kind) WHERE
status IN ('PENDING','RUNNING')` — is the coalescing mechanism: race-free by construction (an
`INSERT` that would violate it fails at the database, not a check-then-insert TOCTOU in Kotlin).
`idx_sync_jobs_claimable` backs the claim scan's `FOR UPDATE SKIP LOCKED`;
`idx_sync_jobs_connection_history` backs the per-connection history list and the "already a RUNNING
job for this connection" check. `SERIAL`/`INTEGER` id, epoch-millis `BIGINT` timestamps — the same
dialect as every other v0.2.0 table.

**The `sync_jobs` prune hard-delete exception.** Unlike every other business entity in this repo,
finished `sync_jobs` rows are hard-deleted, not soft-deleted — see "Soft delete (convention)" above
for why (unbounded job history has no reader that needs it once a row is old, and the same
opportunistic-pruning shape already exists for `revoked_tokens`). `source_connections.purged_at`
(added by this same migration, `ALTER TABLE`) is the companion column A2's PURGE job stamps on
success, independent of `sync_jobs` row retention — deriving "already purged" from job history
would regress once the SUCCEEDED `PURGE` row itself gets pruned.

`sync_cursors` (`ingest/SyncCursors.kt`'s `SyncCursorsService`) has PK `(connection_id, stream)` —
one row per incremental-sync phase within a connection — with a `jsonb` `cursor` column (via
`infra/db/Jsonb.kt`, the V8 binding) whose shape is owned entirely by the stream that reads and
writes it, plus `watermark_at`/`last_completed_at`/`updated_at` bookkeeping columns common to every
stream. The streams themselves (and the cursor shapes they define) land in plan commit 6+.

### Not yet ported from Lettuce / Toadie / Covenant

Nothing remains on the persistence list today — the `metrics` schema's derived star landed at V16
(above); its sprint (commit 8), worklog and epic-plan (commit 9/9b) WRITERS have landed
(`.claude/docs/metrics.md` "Sprint scope, facts and snapshots (D13)"/"Worklog cost facts
(fact_worklog)"/"Epic plans and PV"); `agg_daily_wip`'s writer landed with commit 9f
(`metrics/DeriveWipStep.kt`); only `agg_daily_flow`'s writer is still outstanding.
