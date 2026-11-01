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
  services and publishes them into `Application.attributes` (`FlowUserServiceKey`, `TeamServiceKey`,
  `TokenBlocklistServiceKey`, `PasswordResetServiceKey` today); each service itself lives next to the feature it serves
  (`flowusers/FlowUserService.kt`, `teams/TeamService.kt`, `auth/TokenBlocklistService.kt`, `auth/PasswordResetService.kt`). The Exposed
  table `object`s (e.g. `FlowUserService.FlowUsers`, nested inside their service) are used for queries
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
  (ported from Lettuce) wrapping the plain PostgreSQL R2DBC factory, built by the one shared
  `connectPooledDatabase` (the test harness's direct `sharedDatabaseForTests()` uses it too, with a
  small pool of its own) — never a raw `r2dbc:postgresql://` connect, which opens one backend per `suspendTransaction` with nothing
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
  `SELECT count(*) FROM pg_stat_activity WHERE application_name = 'flow'`. The driver's per-connection
  prepared-statement cache is bounded (`PREPARED_STATEMENT_CACHE_QUERIES` = 256 in `Database.kt`, applied in
  `connectPooledDatabase`, so the test pool has it too; r2dbc-postgresql's default is unbounded and every cached text stays a
  named server-side statement for the connection's life) — it matters because `MultiRowInsert.kt`'s SQL text varies with the row count
  (quantized to a handful of sizes per table). **A multi-row statement is bounded in payload as well as in bind parameters**: `MultiRowInsert.kt` also splits a batch so one statement binds at most
  `MAX_PAYLOAD_CHARS` (1 MiB) of `String` values (a lone heavier row goes alone, never refused there; the split keeps the power-of-two sizes, so no new statement texts of note) —
  before, dense issues' worklogs (540 of ~15 KB per issue) went in statements capped only at the bind cap's ~3,555 rows (~55 MB of `jsonb` each), and two at once OOM-killed a Postgres backend (`build-times.md` WHY 48, case g).
  `insertRows` / `insertRowsIgnoringConflicts` build each row (run the caller's body) lazily, only while the statement that carries it is assembled, and plan the statements exactly as the payload split would over the whole list, so the rows in flight are one statement's cap, not the call's, and each body runs exactly once (`upsertRows` builds them all first: its collapse of repeated keys needs every key; `build-times.md` WHY 51). Exposed lists every statement a transaction ran until it ends (parsed SQL and encoded parameters included): `insertRows` clears the list after each statement, and `JiraRawBatchWrites` (`diffBatch`) after each key read, per-row UPDATE and touch chunk, so a 30,000-row flush keeps no read or update statement (`jira-integration.md` "Stored-size ceilings"); the worklog writer additionally hands in groups of at most 1,024 rows / 512 Ki characters, each normally one statement. **Statement timeouts: use the transaction's `queryTimeout`, nothing else.** Exposed's R2DBC executor *requests* the
  transaction's `queryTimeout` (whole seconds, default 0, never null) from the driver before EVERY statement. The reports use it
  through `ReportService.reportTransaction` (`reports.md` "Query budget"), the post-commit ANALYZE through
  `MetricsStore.analyzeDerivedTables` and the post-PROCESS `norm` ANALYZE through `norm/NormAnalyze.kt` (`SET LOCAL lock_timeout`
  is effective, the statement budget is the `queryTimeout`).
  **The per-connection cache (`infra/db/StatementTimeoutCache.kt`, `build-times.md` WHY 13).** r2dbc-postgresql answers each
  request with its own `SET STATEMENT_TIMEOUT` simple query that Exposed awaits — 43-49 % of all the round trips of a stub
  PROCESS or DERIVE. `connectPooledDatabase` therefore wraps the RAW connection factory (below the pool) in
  `StatementTimeoutCachingConnectionFactory`: each physical connection remembers the value the server last confirmed, and the
  driver call is *sent* only when the requested value differs from it. No reset on release is needed — Exposed requests its
  value before every statement, so the borrower after a report's 30 s sees `0 != 30` and sends it, which is also why a budget
  never leaks to the next borrower (`ReportQueryBudgetTest`). **The hazard it handles:** PostgreSQL reverts a session-level
  `SET` issued inside a transaction when that transaction rolls back, and a statement cancelled by the timeout aborts its
  transaction — a naive cache would then believe the budget is still set and run the next report unbudgeted. The remembered value
  is therefore forgotten on every rollback (also to a savepoint), on a failed commit and on `setAutoCommit(true)`, forgotten
  before a SET is sent and remembered only once it completed under an unchanged generation, and kept across a successful commit.
  **Rule: ONLY the transaction's `queryTimeout` may set the statement timeout.** A `SET LOCAL statement_timeout` may stay in
  force for the rest of its transaction (the cache no longer sends a request that matches its remembered value, so nothing
  overwrites it), and a session-level `SET`, `set_config(…, false)`, `RESET` or `DISCARD` on a pooled connection desyncs the cache
  from the backend. Nothing in `server/src/main` does any of these (grep, 2026-10-06). Deployment assumption: one client
  connection is one server session — a transaction-mode PgBouncer in front of Postgres would break the cache (none today).
  `StatementTimeoutCacheTest` (own one-connection pool) pins convergence, the rolled-back SET, the aborted-transaction canary
  (driver ROLLBACK-tag exception + Exposed's rollback after a failed commit), a budget enforced again after a cancelled
  statement, and the skip with its limit.
  A role that runs the worker
  (`worker`/`all`) refuses to boot unless `maxSize > 2 × ingest.workerSlots` (`configureIngestWorker`): a running DERIVE
  holds two pooled connections at once (its transaction plus `MetricsStore.ensureDimDate`'s own); a lease renewal the
  ticker abandoned at its slack holds one more for up to about a slack (`renewLease` is bounded server-side), at most one per
  slot at a time — deliberately not in the formula, a stall that eats the headroom makes the next renewal fail and the job stop
  (`ingestion.md` "Lease and heartbeat"); raise `maxSize` rather than the formula. Size it as
  `maxSize × replicas + 1` (Flyway's short-lived JDBC connection, `infra/db/Flyway.kt`) well under
  the server's `max_connections`. `maxAcquireTimeSeconds` is a per-attempt deadline, and the effective
  bound is about 2× the setting. r2dbc-pool 1.0.2's `ConnectionPool` builds `create()` as
  `Mono.defer { acquire.flatMap(…).timeout(maxAcquireTime) }.retry(acquireRetry)` and the builder default is
  `acquireRetry = 1` (read from the bytecode): the timeout is an ordinary error to `retry`, so a timed-out waiter is
  re-queued at the BACK of the acquire queue for a second full deadline before the failure surfaces. Flow keeps the
  default on purpose: the same retry is what transparently replaces a connection that fails `LOCAL` validation
  (`prepareConnection` invalidates it and the outer retry acquires another), so a connection closed from the driver
  side — a Postgres or pod restart, a FIN/RST on an idle socket — costs no request or worker job, whereas with
  `acquireRetry(0)` each such stale connection would fail one (a manually enqueued job would be lost). After a
  Postgres restart the pool therefore heals itself as long as at most one stale connection is handed out per
  acquire; size `maxAcquireTimeSeconds` knowing a saturated pool fails after twice it (`ConnectionPoolTest` pins the
  ~2×). A caller that waits past the acquire deadline fails with the pool's timeout
  exception, which `plugins/ErrorHandling.kt`'s catch-all renders as a logged `500` — deliberately NOT a new declared status, since the OpenAPI conformance gate would need it
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
  `ConnectionPoolTest` (bounded concurrency, a saturated pool's acquire timeout, its ~2× deadline, disposal on
  `ApplicationStopped` and on a later module's failed startup, and the seven config range-check
  cases — three of them `idleInTransactionTimeoutSeconds`: −1 and 86401 refuse startup, 0 boots) and `checkDependencyAlignment`'s own Reactor guard.
- **A backend left holding its transaction (2026-10-23, build-times WHY 24).** Two defences, both on every pooled connection
  (built in `connectPooledDatabase`, so the test pool has them too). (1) `infra/db/LeakedSocketGuard.kt`: r2dbc-postgresql 1.1.3
  never closes the socket after a protocol-level connection error ("Response queue is full": its `onError` completes the request
  sink, whose `doAfterTerminate` sets `isClosed` before the `close()` it calls next, so that and every later close is a no-op).
  The backend then keeps its transaction and locks for the life of the process — `idle in transaction` if the rest of the result
  fit in the socket buffers, `active` in `ClientWrite` otherwise, which no server timeout ends. A decorator on the RAW factory
  (below the pool, beside the statement-timeout cache) disposes the netty channel after the driver's close when the client is
  closed but the channel is open; the pool's release validation (`LOCAL`) fails such a connection at once and destroys it, so the
  backend ends and its locks go as the failing transaction returns its connection. It reads two private driver fields by
  reflection; `LeakedSocketGuardTest` pins them AND the driver bug on an unguarded connection (red after an upgrade = the upstream
  fix landed, delete the guard). (2) `idle_in_transaction_session_timeout`, sent as a startup parameter
  (`postgres.pool.idleInTransactionTimeoutSeconds`, `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`, default 300, 0..86400, 0 = off;
  an operator's own `options=` key in the URL wins; as a startup parameter it also overrides an `ALTER ROLE/DATABASE … SET` of the
  same setting): the server ends any pooled session idle inside a transaction for longer —
  the backstop for a client that stopped talking with a transaction open for any other reason (a frozen worker, a coroutine
  stuck mid-transaction). An idle gap cannot outlast its transaction, the longest transaction is a DERIVE (`metrics.md`
  "Performance", scale 20: 18-29 s warm, 38 s for a cold first derive) and no transaction spans an outbound call or a mail send
  (checked 2026-10-23), so 300 s is ~8x the longest legitimate gap; DERIVE's `lock_timeout` is never below it
  (`max(300 s, this)`, wired in `metrics/Metrics.kt`; `metrics.md` "Failure and cancellation"), so a DERIVE queued behind such a
  session outlives it. It is a different setting from the statement timeout, so the "only `queryTimeout`" rule above is
  untouched. `LeakedSocketGuardTest` pins both (the session is ended and a foreign `FOR UPDATE` on its row succeeds).
- **A pooled connection must not pin the frame that created it (2026-10-29, build-times WHY 51, measured fixed in WHY 52).** reactor-netty keeps the subscriber
  chain of the `create()` that opened a channel on the channel (`ChannelOperations.listener`, a `NewConnectionProvider$NewConnectionObserver`
  whose `sink` leads to its `actual` subscriber) for the channel's whole life - `maxIdleTimeSeconds` (600 s) of idleness, never while the
  connection is reused. Through r2dbc-pool's allocator, the pool's borrower and Exposed's `awaitOne` that chain reached the continuation of
  the coroutine that happened to trigger the creation, so a finished job's frame (a dense-worklog flush: a whole 25,000-row batch, +10 MiB)
  stayed reachable from the netty selector for minutes; up to `postgres.pool.maxSize` connections could each pin one, outside the boot-time
  heap fit (`jira-integration.md`). `infra/db/DetachingConnectionFactory.kt` ends the chain at a bridge: it subscribes to the real
  `create()` with a `DetachingBridge`, which forwards the one connection / error / completion to the pool and then DROPS the pool's
  subscriber - after delivery, after a cancel, after an error - so what the channel retains ends at the bridge. It offers the driver an
  EMPTY Reactor context: reactor-netty copies the subscriber context into the observer at connect time and keeps the copy for the
  channel's life, so a caller's context entry (a `ReactorContext` element, a tracing value) would otherwise be pinned the same way. It
  is the OUTERMOST decorator of the raw factory in `connectPooledDatabase` so that a connection it closes is the decorated one (the close
  reaches the socket guard); the pin itself is cut wherever it sits (only what is upstream of the bridge stays on the channel). It
  honours demand, delivers exactly once and CLOSES a connection nobody owns (it arrived after a cancel, or a cancel came between the
  arrival and the demand): never a leaked socket. With Flow's pool configuration that cancel path is defensive: r2dbc-pool subscribes
  to the allocator fire-and-forget and adds a create timeout only when `maxCreateConnectionTime` is set (it is not), so an acquire
  timeout never aborts a connect - the connection reaches the pool, which keeps it.
  The alternatives were rejected: `Sinks.One` keeps nothing but needs the same cancel/late-close handling and gives no demand control, and
  neither reactor-netty nor r2dbc-postgresql 1.1.3 has an option or hook that clears the observer.
  `PooledConnectionPinTest` proves it with weak references on a fresh pool (`initialSize = 0`, so the test's own query creates the connection;
  a 16 MiB array held across the query, the function returned, the connection idle, `System.gc()` must clear it) - sequentially and for three
  concurrent creations; red without the decorator. Its callers run on `Dispatchers.IO`: a test that runs the query directly on the
  `runBlocking` thread keeps a stale reference on that thread's own stack and is red either way, which says nothing about the pool.
  `DetachingConnectionFactoryTest` pins the contract without a database (one delivery, error, a throwing `create()`, empty, demand
  before/after the arrival, a cancel from `onSubscribe` never connects, a cancel before or after the arrival closes the connection, a
  cancel after the delivery never cancels the source, an empty context, the subscriber collectable once served or cancelled).
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

- `TeamService` reads `FlowUserService.FlowUsers` for the manager (V23): the active manager's display name (one
  batched lookup per page), the manager id check on create AND update, and `isActiveManager`'s `Teams` ⋈ `Users` join (the
  capacity guard). `teams.manager_user_id` has no `ON DELETE` rule: users are only ever soft-deleted;
  a future hard purge of users must clear it first, and a future user RESTORE must clear or re-audit the
  manager grants the user held (a soft-deleted manager's id stays in the column, inert only while the user
  is deleted).
- `TeamService.delete` (v0.3.0 M1 commit 3) WRITES `metrics/TeamMembershipService.TeamMembership`
  and `metrics/MetricsSettingsService.Settings` directly, in the SAME transaction as the team's own
  soft delete: it closes/removes the team's D1 Jira-user memberships and bumps the shared
  `config_revision` — see "The `metrics` schema — configuration (V15)" below for why (a deleted
  team must never strand an account behind an `EXCLUDE`-guarded open membership it can no longer
  end).
- `metrics/TeamMembershipService` (v0.3.0 M1 commit 3) queries `teams/TeamService.Teams` directly —
  `list` only requires the team to EXIST (read-before-guard: a soft-deleted team's history stays
  visible), `create` locks it ACTIVE for the whole transaction
  (`TeamService.Teams.lockActiveForWrite`, race-free against a concurrent `TeamService.delete`
  trying to lock the SAME row) — and queries `norm/WorkItemStore.People` directly (an unknown Jira
  account id is `400` — the client-supplied-FK idiom `TeamService.requireActiveUsers` already uses
  for Flow user ids), all inside its own transactions.
- `metrics/TeamMembershipSeedService` (D1 seeding from the Atlassian Team field; `GET /api/v1/jira-teams`, `GET`/`POST
  /api/v1/teams/{id}/jira-memberships/seed[-candidates]`) reads `norm` only through the `WorkItemStore` facade
  (`atlassianTeams`, `teamAssignees` in `norm/NormPickerReads.kt`: `norm.work_items` ⋈ `Connections.active()` on the
  `team_value` JSONB's `id`/`name`, live rows only, plus `norm.people` for display names) and writes only through
  `TeamMembershipService` (`insertMembership`, one `bumpRevision` per batch). Through `TeamMembershipService` it also reads
  `teams/TeamService.Teams` (the active-team check/lock, and `membershipsOfAccounts`' join for the conflicting team's name —
  soft-deleted teams included: their history stays), all inside its own transactions. The task-level filter is the A31
  `hierarchyBucket` rule (`norm/Hierarchy.kt`) applied over `(…, hierarchy_level)`-grouped rows, never re-stated in SQL.
  `metrics/TeamMembershipRoutes.kt`'s list read also calls `WorkItemStore.displayNames` (`norm.people` ⋈ `Connections.active()`,
  `MIN(display_name)` per account, the person's `active` flag ignored) to fill each row's `displayName`.
- `norm/WorkItemStore` reads `ingest/DataSourceService.Connections` directly: every `norm.*` table
  carries a `connection_id` FK to it, and `listPeople`, `distinctAssigneeAccountIds` and
  `distinctWorklogAuthorAccountIds` inner-join it under `Connections.active()`, so people, assignees
  and worklog authors of a soft-deleted connection never surface — read-only, inside its own
  transaction.
- `metrics/MetricsConfigService.referenceData` (v0.3.0 M1 commit 4) reads `teams/TeamService.Teams`
  (active teams) to validate `boards[].teamId` on the metrics-config PUT, inside its own transaction.
- `metrics/DeriveContextBuilder.activeTeamIds` (v0.3.0 M3 commit 9d) reads `teams/TeamService.Teams`
  (active teams) once per DERIVE, inside the rebuild transaction — the A22 rule that now-evaluated
  team columns and the domain owner never name a soft-deleted team.
- `metrics/MetricsConfigService.configuredProjectKeys` (A47, DERIVE's `task_domain` scope) reads the connection's configured `settings.projectKeys` ONLY through the owner's
  `ingest/DataSourceService.read` (the active connection's public response, `jira.projectKeys`) - no `source_connections` table access from `metrics/`, so this adds no table read to the
  list; `metrics/` importing `ingest/` is the allowed direction (`PackageBoundaryTest`).
- `reports/ReportService.filters` (v0.3.0 M4 commit 10a, `GET /api/v1/reports/filters`) reads
  `teams/TeamService.Teams` (active teams), `norm/WorkItemStore.People` (display names for a
  team's current D1 Jira members) and `ingest/DataSourceService.Connections` (id+name for active
  connections, plus the ONE `settings` key `siteUrl` for the issue-key links — never any other `settings` key nor the encrypted API token) directly, plus the `metrics` tables
  `DimSprint`, `DimDomain`, `DimEpic`, `DimTask` (the filter option lists) and `DeriveRuns` (the
  `derivedAt` clock), all inside its own transaction — a read-only reference-data assembly, never
  a write.
- `reports/ReportValidators.kt` (`ReportService.dataStamp`, the report ETag's data stamp) reads, read-only in one `reportTransaction` before
  any report runs, `ingest/DataSourceService.Connections` (active ids, names, `settings`' one `siteUrl` key, `last_sync_succeeded_at`; and every soft-deleted row's `purged_at`), `ingest/SyncJobsService.Jobs` (per connection the newest SYNC/RECONCILE/REPROCESS/PURGE job id and its latest `heartbeat_at`/`finished_at`, one grouped query), `teams/TeamService.Teams` (active ids, names,
  `updated_at`), `metrics/TeamWorkCategoryTargetService.TeamWorkCategoryTargets` (every row: team, category, percent, `updated_at` -- the Costs
  report's query-time input whose PUT bumps no revision) and `metrics/MetricsTables.DeriveRuns` (the newest SUCCEEDED run of every active
  connection, one `DISTINCT ON` query, `newestSucceededRuns`) -- nothing the reports do not already read.
- `reports/ReportSupport.kt` (v0.3.0 M4 commits 10b/10c/10d/12, shared by `reports/CommitmentsReport.kt`,
  `reports/ThroughputReport.kt`, `reports/SprintConsistencyReport.kt` and the estimation reports
  `reports/TaskAccuracyReport.kt`/`EpicAccuracyReport.kt`/`EstimateAdjustmentsReport.kt`) reads
  `ingest/DataSourceService.Connections` (the active-connection
  scope and the `connectionId` existence check), `teams/TeamService.Teams` (the `teamId` existence
  check and team names) and `norm/WorkItemStore.People` (assignee display names) directly, plus
  the `metrics` tables `FactSprint`, `FactSprintSnapshot`, `FactTaskDelivery`, `FactEpicDelivery`,
  `DimSprint` and `DeriveRuns` (the shared sprint/task/epic slices every report builds on), inside
  the calling report's own transaction — read-only.
- The per-report readers of the `metrics` star (all read-only, inside the report's own
  transaction, through `ReportSupport`'s shared slices): `reports/CycleTimeReport.kt`,
  `ReportedTimeRatioReport.kt`, `TaskAccuracyReport.kt` and `EstimateAdjustmentsReport.kt` read
  `FactTaskDelivery`; `EstimateAdjustmentsReport.kt` also reads `FactEpicDelivery`;
  `EpicAccuracyReport.kt` reads `FactEpicDelivery` and `DimEpic`; `CommitmentsReport.kt`, `ThroughputReport.kt` and
  `SprintConsistencyReport.kt` read `FactSprintScope` (`ThroughputReport` also `FactTaskDelivery`).
- `reports/WipReport.kt` (v0.3.0 M5 commit 15, `GET /api/v1/reports/wip`) reads `norm/WorkItemStore.Statuses`
  (status names for `by=STATUS`), `norm/WorkItemStore.BoardColumns` (the mapped board's columns for `by=COLUMN`, read
  at query time so a board edit shows up without a re-derive) and `metrics/MetricsConfigService.BoardTeamMap` (which
  board a team owns) directly, inside its own transaction — read-only, and `metrics/MetricsTables.AggDailyWip` for the
  series itself. `reports/BacklogReport.kt` (`GET /api/v1/reports/backlog`) reads `metrics/MetricsTables.AggDailyFlow`
  (the backlog trend) and `metrics/MetricsTables.FactSprint` (the mean `delivered_md` behind the backlog in sprints)
  the same way. `reports/SnapshotSupport.kt`, shared by both, reads `metrics/MetricsTables.DeriveRuns` (the per-connection
  newest successful run, via SQL `max()`) for the last-derived-day cut-off, and `ingest/DataSourceService.Connections.backfillFrom`
  (`backfillStart`: the connections in scope that have derived, read-only) for report 9's series start. `metrics/DeriveWipStep.kt` (the TEAM/TASK statement)
  reads the same column straight off `public.source_connections` in its `day_range` SQL (a scalar sub-select on the primary key), so the
  WIP aggregate starts no earlier than the connection's `backfill_from`.
  `reports/WipDoneBand.kt` (the DONE band of report 9, computed at query time, decision B / A34) reads, read-only in the report's
  transaction and as raw SQL (`exec`, the `JiraRawViewReads` precedent: it joins the global `metrics.dim_date` with the interval tables
  in one statement), the `metrics` tables `ItemStage`, `DimTask`, `DimEpic`, `DimDomain`, `FactTaskDelivery` (`credit_team_id`, `domain_key`,
  `assignee_account_id_at_done`), and `DimDate`; it imports `coveringAt` from `metrics/DeriveWipStep.kt`.
  `reports/BacklogTouches.kt` (the backlog report's `touchedWithinDays` filter, A38) reads, read-only in the report's transaction and as
  raw SQL (`exec`, the `WipDoneBand.kt` precedent), `metrics/DeriveFlowStep.kt`'s `backlogRowsCte` (so `DimDate`, `DimTask`, `ItemStage`,
  `ItemEstimate`, `TaskDomain`, `TaskEpic`, `TaskSprint`, `DimSprint`) plus `DimDomain` (the owner team), `FactTaskDelivery.created_at`,
  `norm/WorkItemStore.Worklogs.started_at` and — **the one report read of `raw.*`** — `jira/JiraRawStore.Changelogs` (`issue_id`,
  `created_at` only, an index-only probe per item through `idx_raw_jira_changelogs_issue`): the "any change of any field" touch cannot
  come from `norm` (the tiled `work_item_field_changes` keep tracked fields only). No other report reads `raw.*`.
- `reports/AgingWipReport.kt` and `reports/BlockedTimeReport.kt` (v0.3.0 M5 commit 15 part b, `GET /api/v1/reports/aging-wip`
  and `/blocked-time`) read `norm/WorkItemStore.WorkItems` (issue key and summary, via the shared `workItemLabels`) and
  `norm/WorkItemStore.People`/`teams/TeamService.Teams` (through `orgGroups`/`accountDisplayNames`) directly, plus the
  `metrics` tables `FactTaskDelivery`, `FactEpicDelivery`, `DimEpic` and `ItemBlocked` -- all read-only, inside the report's
  own transaction.
- `reports/EpicProgressReport.kt` + `EpicProgressTargets.kt`/`EpicProgressEvm.kt`/`EpicProgressRows.kt` (v0.3.0 M5 commit 15c, `GET /api/v1/reports/epic-progress`) reads the `metrics` tables
  `AggDailyFlow` (the per-day PV/EV/AC increments), `DimDate`, `DimEpic`, `DimDomain`, `FactEpicPlan`, `FactEpicDelivery` (the budget
  fallback) and `FactWorklog` (the team foreign-work share), plus `teams/TeamService.Teams` (team names and the active-team
  list of the unit drill) -- all read-only, inside the report's own transaction.
- `reports/CostsReport.kt` + `CostsBreakdown.kt` (v0.3.0 M5 commit 17b as the cost matrix, `GET /api/v1/reports/costs`) read `metrics/MetricsTables.FactWorklog`
  only (grouped sums of `md`: the foreign/total split and the activity-type / work-category slices), plus the shared scope reads of
  `resolveReportScope` -- all read-only, inside the report's own transaction. `reports/CostsSprints.kt` (A44: `fact_worklog` per listed sprint window, joined to `dim_task.epic_id`; the sprint rows come from `resolveReportScope`) and `reports/CostsDrill.kt` (`GET /api/v1/reports/costs/items`) add
  `metrics/MetricsTables.DimTask` (the sub-task → parent roll-up) and `norm/WorkItemStore.WorkItems`/`People` (issue keys, summaries and author display
  names, through `drillItems`/`accountDisplayNames`). `reports/CostCapacity.kt` (A39/A41, the per-person capacity kernel, no longer read by Costs) is read
  only by Compare people (below): `metrics/MetricsTables.DimSprint` (the team's sprints and their dates), `metrics/TeamMembershipService.TeamMembership`
  (the team's dated roster) and `metrics/SprintCalendarService.SprintCalendars` ⋈ `ingest/DataSourceService.Connections` (active
  connections only) through `metrics/SprintCalendarReads.kt`.
- `reports/Home*.kt` (the Home overview, `GET /api/v1/reports/home` and its `/items` drill) reads, all read-only inside the report's own
  transaction: `ingest/DataSourceService.Connections` (id, name and `last_sync_succeeded_at` of the active connections -- the freshness line takes the
  last sync from the connection row, never from `sync_jobs`, whose rows are pruned), `teams/TeamService.Teams` (the active teams),
  `norm/WorkItemStore.WorkItems` and `People` (issue keys, summaries and display names of the listed drill items, through `drillItems`/`workItemLabels`/`accountDisplayNames`), `metrics.settings` through `metrics/MetricsSettingsService.readWithCalendar`, and the `metrics` tables `FactTaskDelivery`,
  `FactEpicDelivery`, `DimEpic`, `FactSprint`/`DimSprint`/`FactSprintSnapshot` and `DeriveRuns`, mostly through the other reports' own fetches.
- `reports/ComparePeople*.kt` (A41, `GET /api/v1/reports/compare-people` and its `/items` drill) reads, all read-only inside the report's own
  transaction: `metrics/MetricsTables.FactTaskDelivery` (the done, accuracy, adjustment and blocked populations through the other reports' own
  fetches, plus the work-mix keys) and `FactWorklog`; `item_stage` ⋈ `task_assignee` ⋈ `dim_task` (⋈ `FactTaskDelivery` for the held tasks' `started_at`)
  through `reports/WipUserReport.kt`'s `readWipUserDayCounts` / `readWipUserHeld` / `readWipUserTaskCounts` (the people's WIP across every team, no team join, counted in Postgres; `readWipUserHeld` adds the tasks held on the last days with their `started_at` as a second statement); `norm/WorkItemStore.People` through
  `accountDisplayNames`; and, only for a caller the route grants per-person capacity (an ADMIN or the manager of every team involved, re-checked
  against the memberships read in the same transaction): `metrics/TeamMembershipService.TeamMembership` (the two people's dated memberships, any team)
  and, per team, `reports/CostCapacity.kt`'s `loadCostCapacity` reads (`DimSprint`, the team's roster and `SprintCalendars` ⋈
  `ingest/DataSourceService.Connections`, active connections only). The route's pre-check (`comparePeopleTeamIds`) reads `TeamMembership` and
  `teams/TeamService.isActiveManager` reads `Teams` ⋈ `flowusers/FlowUserService.FlowUsers`.
- `reports/PersonTrend*.kt` (A46, `GET /api/v1/reports/person-trend`) reads, read-only inside the report's own transaction, exactly the tables Compare people's cells do and
  no others, through the same functions: the done, cycle, accuracy, blocked and worklog populations (`FactTaskDelivery`, `FactWorklog`, over the whole range once), the WIP statements of
  `reports/WipUserReport.kt` (`readWipUserHeld`: the day counts and, as a second statement, the tasks held on the windows' last days with their `started_at`), `DeriveRuns` and `source_connections.backfill_from`
  (`derivedCoverage`, `backfillStart`, for the coverage labels), and `People` through `accountDisplayNames`. No capacity. For the team reference (`PersonTrendReference.kt`) it also reads `metrics/TeamMembershipService.TeamMembership` (the person's dated memberships, then the picked teams' rosters, by `readPeopleMemberships` / `readTeamMemberships`) and `teams/TeamService.Teams` for the names (`teamNames`, soft-deleted teams included: a closed roster keeps its history).

- `metrics/TeamWorkCategoryTargetService` (V32, `GET`/`PUT /api/v1/teams/{id}/work-category-targets`) reads `teams/TeamService.Teams` (the
  active-team check, `lockActiveForWrite` on the write), `ingest/DataSourceService.Connections` (active connections only) and, same package,
  `MetricsConfigService.WorkCategoryMap` (which categories a team can choose); it WRITES only
  `metrics.team_work_category_targets` — see "Team work-category targets (V32)".
- `reports/CostsCategories.kt` (Costs, domain-model A45) READS, in the report's own transaction through `metrics/TeamWorkCategoryTargetReads.kt`
  (`storedWorkCategoryTargets`, `availableWorkCategories`), `metrics.team_work_category_targets`, `MetricsConfigService.WorkCategoryMap` and
  `ingest/DataSourceService.Connections` (active connections only), beside `fact_worklog`; it writes nothing.
- `metrics/SprintCalendarService` (V24, capacity management; `GET`/`PUT /api/v1/teams/{id}/capacity|sprint-calendars`) reads
  `teams/TeamService.Teams` (the active-team check on every call, `lockActiveForWrite` on the write paths) and, through
  `TeamService.isActiveManager`, `Teams` ⋈ `flowusers/FlowUserService.FlowUsers` (the non-admin grant re-check inside the write
  transaction); `norm/WorkItemStore.People` (display names, the known-account check — of ACTIVE connections only); `ingest/DataSourceService.Connections`
  (active connections only, joined to the sprint rows) and `metrics.dim_sprint` (a sprint's dates, team and last-derived
  figure — read only). It WRITES only its own two tables, and bumps `MetricsSettingsService.bumpRevision` (same package).
  `TeamMembershipService.memberAccountIds` serves it the roster overlapping a sprint.
- `metrics/DeriveWorklogSql.kt` (the DERIVE worklog step, build-times WHY 31) READS, as raw SQL in DERIVE's own transaction and beside the `metrics`
  tables the same run wrote (`dim_task`, `dim_epic`, `dim_domain`, `task_domain`, `task_epic`, `team_membership`), `norm.work_items`,
  `norm.work_item_worklogs` and `norm.work_item_field_intervals` (`SPRINT`, `ASSIGNEE`); it WRITES only `metrics.fact_worklog`. `metrics/DeriveWipStep.kt` (the TEAM/TASK statement)
  also reads `norm.work_item_field_intervals` (`SPRINT`) the same way, for the sprint team on each day.
- `reports/DeepDiveOptions.kt` (`GET /api/v1/reports/deep-dive/sprints|epics`, the deep dive's picker lists) reads
  `metrics/MetricsTables.FactSprintScope`, `FactTaskDelivery`, `DimSprint`, `DimDomain` and `DimEpic`, plus
  `ingest/DataSourceService.Connections` (the active-connection scope, `resolveConnectionScope`) -- all read-only, each
  list in its own transaction.

- `reports/DeepDiveReport.kt` + `DeepDiveSelection.kt` (`GET /api/v1/reports/deep-dive`, the deep dive itself) read `metrics/MetricsTables.FactSprintScope`,
  `FactTaskDelivery`, `DimSprint`, `DimDomain`, `DimEpic`, `DimTask` (sub-task parents), `ItemStage`, `FactWorklog`, `FactEpicPlan` and
  `DeriveRuns` (via `deriveClocks`/`deriveStamp`), plus `norm/WorkItemStore.WorkItems` (task summaries) and `People` (author names, via
  `accountDisplayNames`) and `ingest/DataSourceService.Connections` (the connection scope) -- all read-only, one query per table for the whole task
  set, in the report's own transaction.

- `reports/DataQualityReport.kt` and its helpers (`DataQualityTasks.kt`, `DataQualityEpics.kt`, `DataQualityConfig.kt`, `DataQualityHierarchy.kt`,
  `DataQualityAssembly.kt`; v0.3.0 M5 commit 17, `GET /api/v1/reports/data-quality`) read, all read-only inside the report's own
  transaction: `metrics/TeamMembershipService.TeamMembership` (the dated roster behind the member-day denominator),
  `norm/WorkItemStore.Statuses`/`Boards`/`Sprints` (the mapping-gap findings) and `WorkItems` (`DataQualityHierarchy.kt`: the live
  `hierarchy_level >= 2` rows of the items-above-the-epic finding, A31, read with their key and summary directly), the per-connection config tables
  `metrics/MetricsConfigService.StatusStageMap`/`BoardTeamMap`/`FieldConfig` (plus a distinct-connection existence probe of
  `DomainMap`/`ActivityTypeMap`/`WorkCategoryMap`, `readConnectionMappings` -- the light
  counterpart of `effectiveConfig`'s stored-else-defaults rule) and the `metrics` tables `FactTaskDelivery`, `FactEpicDelivery`, `FactWorklog`,
  `FactSprint`, `FactSprintSnapshot`, `DimEpic`, `DimDomain`, `ItemStage` and `DeriveRuns` (incl. its `row_counts` JSON).
- `auth/TokenBlocklistService.accessVerdict` (the per-request access-token check, `plugins/Security.kt`) READS
  `flowusers/FlowUserService.FlowUsers` (`role`, `credential_revision`, `marked_as_deleted`, by primary key) and its own
  `revoked_tokens` (an `EXISTS` on the `jti`) in ONE uncached statement per authenticated request; it writes nothing.
- `auth/PasswordResetService` (V35) READS `flowusers/FlowUserService.FlowUsers` (the `active()` / `credential_revision` / `email` predicate
  of a grant's liveness, and `FOR UPDATE` on the user row) and WRITES `users.password_hash` / `password_changed_at` / `credential_revision`
  through `flowusers`' `updatePasswordInTransaction` inside its own transaction (the grant is consumed in the same transaction).
- `plugins/Health.kt` (`GET /api/v1/ready`, the readiness probe) READS `flowusers/FlowUserService.FlowUsers` (`id`, `LIMIT 1`), a bounded round trip
  that proves the database answers; it writes nothing and returns no row.
- `telemetry/StorageCollectors.kt` (V36, the storage panel) reads, OUTSIDE the telemetry cluster transaction (the heavy phase: each statement is a short
  transaction of its own, `HeavyCollectors.kt`): the catalogs (`pg_class`, `pg_namespace`, `pg_attribute`, `pg_database_size`, `pg_total_relation_size`) for
  sizes and for the tables of `raw`/`norm`/`metrics` that have a `connection_id` column (DISCOVERED, never listed), and for each such table
  `SELECT connection_id, count(*) … GROUP BY connection_id` (plus the live-issue count of `raw.jira_issues`) - row COUNTS only, never a payload; it writes nothing
  outside `ops`. The sync queue's depth comes only through `ingest/SyncJobsService.statusCounts()` (`telemetry/ClusterCollectors.kt`), never a read of
  `sync_jobs` from telemetry.

- `telemetry/TelemetryRoutes.kt` (V36, the ADMIN read API: `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`) READS `ops.telemetry_processes` /
  `ops.telemetry_samples` (its own schema; one SELECT per transaction under `queryTimeout` 5 s, the history grouped per role/metric/label/bucket in SQL)
  and, for everything outside `ops`, only through the owners' read methods, all inside the telemetry read transaction: connection id, name, enabled / soft-delete flag
  and sync freshness via `ingest/DataSourceService.connectionHealth()` (columns `id`, `name`, `enabled`, `marked_as_deleted`, `last_sync_succeeded_at`,
  `consecutive_failures`, `last_sync_error_code`, `next_sync_at`, the not-yet-purged rows only - NEVER `settings`, `secret` or `profile`); the per-connection queue, the latest
  SYNC job (and the latest with `progress.backfill = 1`) and the read-time bucket aggregation of finished SYNC jobs (runs, failures, duration, the four `jira*` progress sums) via
  `ingest/SyncJobsService.queueByConnection` / `latestSyncs` / `bucketStats` (`sync_jobs`, `status`, `started_at`, `finished_at`, `progress` keys, `kind = 'SYNC'`); the latest ended
  DERIVE run per connection and the bucket aggregation of ended runs (runs, failures, duration, rows written = the sum of the integer `row_counts` entries) via
  `metrics/MetricsStore.latestRuns` / `runBuckets` (`metrics.derive_runs`, published as `MetricsStoreKey`); `historyFrom` reads `min(finished_at)` of the ended rows through `SyncJobsService.oldestFinishedAt` / `MetricsStore.oldestFinishedAt`. Read-only; the aggregations bound `finished_at` to `[from, to)`.

The "is this team there and active" check every one of those reads runs is ONE function, `teams/TeamActive.kt` `Teams.requireActive(teamId) { failure }` (the caller picks
the status: `Teams.requireActiveOrNotFound` for a path team, a `400` for the reports' `teamId` filter); the row-locking twin is `lockActiveForWrite`.
- `ingest/SyncStatusRoutes.kt` (the ADMIN status view) READS `gitlab/GitLabRawStore.counts(connectionId)` (V41-V43: row COUNTS of `raw.gitlab_projects` /
  `raw.gitlab_users` / `raw.gitlab_commits` by their markers - live, merge, tombstoned, files pending - and the merge-request counts (`mrCounts` over `raw.gitlab_merge_requests` / `raw.gitlab_mr_events`): live, merged, events, details pending (the stream's own backlog predicate), diff stats missing, deleted (tombstoned by a reconcile) and details unread (live, of a live project, any `details_error`) - never a payload, a commit's title, author or path, or a merge request's title, branch or username) for a GITLAB connection, through the store published as `GitLabRawStoreKey` (absent when GitLab is off
  in the process: `counts.gitlab` is then null). `gitlab/GitLabConnector` WRITES `raw.gitlab_*`, its `sync_cursors` rows (the streams' own and `code_process`, whose three fingerprints are the configuration the norm rows were computed under), `norm.code_*` (V44) ONLY through `CodeActivityStore` (PROCESS's replace and PURGE's drains, never a route) and `source_connections.profile` through `DataSourceService.updateProfile` (the PROFILE stream, like Jira's); it READS
  the connection through `DataSourceService.readGitLabForSync` (the decrypted token and the EFFECTIVE path exclusions, for the connector alone) and the code layer's aggregates through `CodeActivityStore` for the profile. `CodeActivityStoreKey` is published even when GitLab is off, so `identities/` can read `norm.code_*` through the store.
- `identities/` READS `norm.code_identities` and the `norm.people` email matches through `CodeActivityStore` (`identityRows`, `identityRow`, `identityKeysOf` for the orphan rule across the host's sources, `proposalMatches`), `WorkItemStore.displayNames` (`norm.people` of ACTIVE connections) for the PUT's account check and the linked names, `source_connections` through `DataSourceService.read` (the path's data source) and `DataSourceService.list` (the other active sources on the same host); WRITES only `public.code_identity_links` (its own table).
- **Issue-key reads (V46, "Merge request issue keys (V46)"; the first reader is Report 21's drill, 1.46.0 - `reports/IssueKeyScope.kt` and `CodeActivityDrillLabels.kt`: the candidates of the LISTED merge requests, the Jira sources' configured project keys through `DataSourceService.activeJiraProjectKeys` - the Jira twin of `activeGitLabBaseUrls`, also read by the `keys:` stamp line - and the `issuekey` change rows; `norm.work_items` by key is read by the task-side report that follows):** `reports/` MAY READ `norm.code_mr_issue_keys` (joined to the LIVE rows of `norm.code_merge_requests`), `norm.work_items` BY `issue_key` (`idx_norm_work_items_issue_key`) and the `norm.work_item_field_changes` rows with `field_id = 'issuekey'` (`from_text` / `to_text`: the old keys of a moved issue), read-only, with raw SQL over the Exposed table objects like Report 21; it never writes them. `gitlab/GitLabConnector` WRITES `norm.code_mr_issue_keys` only through `CodeActivityStore` (PROCESS's replace, PURGE's drain). Besides the owner's own facade (the GitLab PROFILE's candidate counts, `CodeActivityStore.mergeRequestFigures`), only `reports/` reads the table, and no code path reads a Jira table from the GitLab side (`norm` references neither connector).
- **Report 21 (A50, built 1.39.0; `reports.md` "Report 21"):** `reports/CodeActivity*.kt` READ `norm.code_commits`, `norm.code_merge_requests`, `norm.code_mr_events` (and `code_projects` for the drill labels: the drill, 1.41.0, reads `path_with_namespace`, the merge request's `iid`/`title` and the commit's `title` for the LISTED rows only, `CodeActivityDrillLabels.kt`) with raw SQL over the Exposed table objects of `norm/CodeActivityStore.kt` (read-only, no new store method; `reports/` may import `norm/`, never the reverse), the identity links ONLY through `IdentityResolver` (`accountsByKey`, `keysByAccount`, and a new `decidedKeys` for the BOT/IGNORED actors: `reports/` never touches `CodeIdentityLinkStore` or the table), `source_connections` through `DataSourceService.activeGitLabBaseUrls` (the hosts), and the dated memberships from `TeamMembershipService.TeamMembership` (read-only, the `readPeopleMemberships` precedent, then `teamAt`). It writes nothing and adds no table, index or migration (the performance run, `build-times.md` WHY 61, found no statement that needs one: no `merged_at` or other time index). `CodeActivityStamp.kt` READS `source_connections` (GITLAB rows), their `sync_jobs` and `public.code_identity_links` (count and newest `decided_at` per host) for the stamp lines, and `CodeActivityReport.kt` the stored data profile (`DataSourceService.readProfile`) for `sources[]`; `PackageBoundaryTest` carries the rule that `identities/` is referenced only by `reports/` (the routes are wired from `application.yaml`, so the composition root names it nowhere).

List each new cross-feature read/write here as it lands — the list IS the permission.

### Schemas

Flow's PostgreSQL database is split across schemas (v0.2.0 plan §0 A3, the main-session
amendment over the architect's original all-`public`-with-prefixes recommendation):

- **`public`** — the v0.1.0 foundation tables (`users`, `teams`, `revoked_tokens`,
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
  `sprints`, `boards`, …) — landed at V13 (plan commit 8a, "The normalized layer (V13)" below); the code layer's
  `code_projects`, `code_identities`, `code_commits`, `code_merge_requests` and `code_mr_events` joined it at V44 ("The normalized code layer (V44)" below), `code_mr_issue_keys` at V46 ("Merge request issue keys (V46)").
- **`metrics`** — the metrics layer's own schema: configuration lands first (V15, v0.3.0 M1 commit
  3, "The `metrics` schema — configuration (V15)" below); the derived star (dimensions/bridges/
  facts/daily aggregates) lands at V16, v0.3.0 M3 commit 7 ("The `metrics` schema — the derived
  star (V16)" below).
- **`ops`** — Flow's own operational telemetry (V36, "Telemetry (V36)" below): the process registry and the metric samples each
  process writes about itself. Numbers and labels only, never project data.

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
  (the canonicalized, TRIMMED `search/jql` issue document, `infra/json/CanonicalJson.kt`, "The trimmed payloads" below) and its `sha256`.
  `changelog_synced_at`/`worklogs_synced_at` are populated by the CHANGELOGS/WORKLOGS streams (plan
  commit 7, A1, `jira/JiraChangelogStream.kt`'s `markChangelogSynced`,
  `jira/JiraWorklogStream.kt`'s `markWorklogsSynced`) as of V11. `needs_processing`/`processed_at`/
  `processed_hash`/`processing_version` drive the PROCESS step (plan commit 8a, landed —
  `jira/JiraProcessStream.kt`, see "The normalized layer (V13)" below): `markChangelogSynced`/
  `markWorklogsSynced` flag `needs_processing = true` on every issue they touch, and
  `JiraRawStore.issuesToProcess` claims the resulting backlog, ascending issue id, batches of 50.
  `deleted_at`/`moved_out_at` are tombstone columns the RECONCILE stream sets (V12,
  `jira/JiraReconcileStream.kt`); the ISSUES stream also sets `moved_out_at` at the start of every
  SYNC for stored rows whose project left the connection's scope (`JiraRawStore.markOutOfScopeProjects`,
  by project id, local and idempotent), and clears both on a resurrecting `upsertIssue`. Nothing else
  sets them — a key/project change during an ISSUES page is just an ordinary column update, not a
  distinct "move" code path. Three partial indexes back the streams' own claim scans:
  `idx_raw_jira_issues_needs_processing` (PROCESS, plan commit 8a),
  `idx_raw_jira_issues_stale_changelog` (CHANGELOGS, plan commit 7, V10,
  `changelog_synced_at IS NULL AND deleted_at IS NULL`, read by
  `JiraRawStore.staleChangelogIssueIds`) and `idx_raw_jira_issues_stale_worklogs` (WORKLOGS, plan
  commit 7, V10, A1's per-issue backfill scan, same shape, read by `JiraRawStore.staleWorklogIssueIds`).
- **`raw.jira_entities`** — PK `(connection_id, kind, entity_id)`, one row per REFERENCE-stream
  entity kind (`JiraEntityKind`: `FIELD`, `STATUS`, `STATUS_CATEGORY`, `PROJECT`,
  `PROJECT_STATUSES`, `ISSUE_TYPE`, `PROJECT_FIELDS` (the optional field-scheme step, one entity per project key, after `ISSUE_TYPE`: it reads the hierarchy levels), `PRIORITY` (no longer fetched since 2026-10-08; old rows are tombstoned), `RESOLUTION`, `ISSUE_LINK_TYPE`, `USER`, `BOARD`,
  `BOARD_CONFIGURATION`, `SPRINT` — no CHECK constraint, since the Kotlin enum is the whitelist and
  the column drives no SQL-level behavior, the `users.role`/`sync_jobs.status` idiom reserved for
  columns a CHECK usefully pins). `entity_id` is `VARCHAR(255)` (V20; 50 in V10), not `BIGINT`, because Jira ids are
  numeric for most kinds but an opaque `accountId` string for `USER` (76 characters for a JSM customer). `idx_raw_jira_entities_last_seen`
  (`connection_id, kind, last_seen_at`) backs the REFERENCE stream's end-of-pass tombstone sweep.
- **sha256 change detection (both tables).** `JiraRawStore.upsertIssue`/`upsertEntity` (and their whole-page forms `upsertIssues`/`upsertEntities`, `jira-integration.md` "Batched raw writes") canonicalize
  the incoming payload (`infra/json/CanonicalJson.kt`) and hash it; an unchanged hash on a
  non-tombstoned row bumps only `fetched_at`/`last_seen_at` (`RawUpsertOutcome.UNCHANGED`) — the
  payload/`changed_at` columns are untouched, so `changed_at` tracks genuine content changes only,
  never a re-fetch that happened to return the same document.
- **The trimmed payloads (`jira/JiraPayloadTrim.kt`, 1.17.1).** The raw store keeps what Flow reads, not everything Jira sends; the
  trim runs on every write path (`upsertIssue`/`upsertIssues`, `insertChangelog(s)`, `upsertWorklog(s)`) BEFORE canonicalization and
  hashing, so the stored text and its `sha256` agree. An issue loses `fields.comment`, `fields.worklog`, `fields.description`,
  `fields.attachment` (free text and copies of data held elsewhere: worklogs have their own table, a description or comment can
  carry personal data), `fields.subtasks` (embedded child issues; the edge is the child's own `parent`) and `fields.environment` (free
  text, empty on every real install measured); each `fields.issuelinks[]` element is reduced to `{id, type: {name, inward, outward},
  inwardIssue/outwardIssue: {id, key}}` (`jira/JiraIssueLinks.kt`; Jira embeds the linked issue's summary, status, priority and type
  with icon URLs and `self` links in every link, ~15 % of the stored issue text on a real install - trim rules v2); and its `fields.assignee`/`reporter`/`creator` are reduced to `{accountId, displayName, accountType}`
  (`jira/JiraUserReferences.kt`; the normalizer reads the assignee's `accountId`/`displayName` and the reporter's `accountId`).
  Everything else stays verbatim: every other system field, **every `customfield_*` (nested ADF included - a user picker in a custom
  field keeps its full user)**, `timetracking`, `expand`, `self`. The raw issue inspector therefore returns
  the trimmed document. **The equivalence property** (`JiraPayloadTrimTest`): normalizing a trimmed payload gives exactly the
  rows the untrimmed one gives, over a rich synthetic issue and over every raw row of the stub fixture with the dropped keys
  injected - a key joins the drop list only after every reader was grepped (`ingestion.md` "Normalized layer"). A change confined
  to a dropped key (a new comment, an e-mail address) is no content change: the hash is the same, `UNCHANGED`, `changed_at` stays
  put. Rows stored before the trim existed: `ingestion.md` "Upgrade path" (the one-off TRIM rewrite is gone).
- **A stored-row size bound (1.24.1, no migration).** Every write path also refuses an item over its stored-size ceiling BEFORE the diff
  (`RawUpsertOutcome.REFUSED`, `jira/JiraStoredCeilings.kt`): an issue or entity over `jira.maxStoredPayloadBytes` as canonical text, a history
  over 512 KiB and a worklog over 16 KiB as the text arrived. Nothing is written; an existing row keeps its payload, hash and `changed_at` and
  only has `fetched_at` (issue, worklog) or `last_seen_at` (entity) touched, so the end-of-pass sweep never tombstones it. PROCESS reads rows by
  stored bytes (`octet_length(payload::text)`; `jira.processPageBytes`) and reads a whole entity kind only in chunks of at most that many bytes (`JiraEntityReads`; `ingestion.md`
  "Normalized layer"). A refusal is no change: it does not set `needs_processing` and is no progress for the pause.
- **Tombstones and resurrection.** `raw.jira_entities.deleted_at` is set by
  `markEntitiesDeletedNotSeenSince` at the end of a REFERENCE pass, for every kind, over rows whose
  `last_seen_at` is older than the pass's own start time (`passStartedAt`) and not already deleted —
  entities genuinely absent from this pass, not ones simply not yet reached (a resumed pass's
  `passStartedAt` is preserved across restarts, so a partial pass never tombstones entities its own
  later steps haven't visited yet). A tombstoned entity or issue seen again on a later pass/page has
  its `deleted_at`/`moved_out_at` cleared by the same upsert path that would otherwise report
  `CHANGED` — reported instead as `RawUpsertOutcome.RESURRECTED`.
- **PURGE (plan §0 A2).** `JiraRawStore.purgeIssuesBatch`/`purgeEntitiesBatch` (500 rows per call,
  `JIRA_PURGE_BATCH_SIZE`) delete one connection's rows in batches; `JiraRawStore.purgeAll` (in `jira/JiraRawPurge.kt`; an
  extension function) drains all four `raw.jira_*` tables (issues/entities plus the V11
  changelogs/worklogs tables below) by looping each batch call until it deletes zero rows.
  `JiraConnector.purgeSteps` wires this as the PURGE job's one connector-owned cleanup step.

### The Jira changelog/worklog raw store (V11)

`raw.jira_changelogs` and `raw.jira_worklogs` (v0.2.0 plan §4/§7 V11, A1, `jira/JiraRawStore.kt`)
join `raw.jira_issues`/`raw.jira_entities` (V10) in the `raw` schema — the CHANGELOGS/WORKLOGS
streams' target (`jira/JiraChangelogStream.kt`, `jira/JiraWorklogStream.kt`).

- **`raw.jira_changelogs`** — PK `(connection_id, history_id)`, one row per Jira changelog history.
  **Append-only**: a history is immutable once Jira creates it, so there is no diff/tombstone rule
  here (unlike every other raw table) — `JiraRawStore.insertChangelog` (and its batch form `insertChangelogs`, one multi-row insert) is a plain `ON CONFLICT DO
  NOTHING` keyed by the PK, safe to re-run after a crash, and correct whether the SAME history
  reaches here via the bulkfetch batch path or the per-issue fallback path (both dedup identically).
  `payload` is the history object as Jira returned it (bulkfetch's `changeHistories[]` entry
  or the per-issue fallback's `histories[]` entry — same shape either way), canonicalized — **with one exception, the slim
  author** (below). History
  ids are numeric and globally unique on a real Jira instance, but the PK still scopes to
  `connection_id` like every other raw table. `idx_raw_jira_changelogs_issue`
  (`connection_id, issue_id, created_at`) backs the normalization layer's future per-issue changelog
  replay (plan §8), the pipeline test's own per-issue assertions — every history for one issue,
  oldest first — and the backlog report's touch read (`reports/BacklogTouches.kt`, A38: an index-only probe per item).
- **The trimmed payloads (`jira/JiraPayloadTrim.kt`) - the exceptions to "exactly as Jira returned it".** Jira inlines a
  whole `USER` object (`self`, four `avatarUrls`, `emailAddress`, `timeZone`, `active`, ...) as a history's `author` and a
  worklog's `author`/`updateAuthor`: about 75 % of a history's bytes (about 1,350 B a row, 257k rows on a real tenant, where
  Postgres never compresses a row under its ~2 KB TOAST threshold) and about 83 % of a worklog's. That is a denormalized copy of
  the `USER` entity (`raw.jira_entities`, which keeps the full object once), not information about the change, and Flow reads only
  `accountId` (`JiraChangelogStream.storeHistory`, `JiraNormalizer`). So before a history or worklog is canonicalized, hashed and
  stored, each `author`/`updateAuthor` OBJECT is replaced with `{accountId, displayName, accountType}` (only the keys that are
  present; `accountType` - `atlassian`/`app`/`customer` - stays so a later rule can tell humans from bots such as Jira
  Automation). A non-object value (absent, `null`, a string) is left untouched. On top of that, **a history loses its `items`
  for a description edit** (`fieldId` `description`, or an item with no `fieldId` whose `field` is `description`) **and for a
  remote link** (`field` `RemoteIssueLink` or `RemoteWorkItemLink`, which carry no `fieldId`; an item that has a `fieldId` is judged by it alone, so a custom field named "description" keeps its history): the normalizer tracks a closed list
  of items by `fieldId` (status, assignee, priority, resolution, issuetype, project, issuekey, duedate, the Sprint/Flagged/Rank/
  story-points ids, every `customfield_*`) plus the parent spellings by name, and none of these is on it. The other items stay
  (`WorklogId`, `timespent`, `Attachment`, ... are unread but cheap). **A history ROW is never removed, not even when every item
  was stripped and it keeps `items: []`**: `reports/BacklogTouches.kt` counts every history of an issue as a touch (the backlog
  report's `touchedWithinDays`), whatever it changed. A worklog loses `self` and `comment`; its timestamps, `timeSpent*`,
  `visibility` and `properties` stay. The trims run inside every writer (`insertChangelog`, `upsertWorklog` and their batch forms),
  **before** `sha256Hex`: a re-sync of an unchanged worklog hashes identically (`UNCHANGED`, no `changed_at` move), and a change
  confined to a dropped key (an avatar URL, a time zone, a comment) is not a content change. It also drops `emailAddress` (personal
  data) from those rows - data minimization (`security.md`).
- **No rewrite of stored rows (the TRIM stream is gone).** Until 1.25.0 a one-off TRIM stream rewrote rows stored before the trim existed (`payload` and `sha256` only; trim rules v1, then v2), the only
  sanctioned rewrite of these append-only tables; it was removed once every install had walked it to `DONE` (`ingestion.md` "Upgrade path": an install still holding older rows runs one SYNC on a build that
  has it first). An UPDATE leaves old row versions behind, so a table that was rewritten keeps its size until `VACUUM (FULL, ANALYZE)` (locks the table, needs free disk of about its size); plain autovacuum only
  reuses the space. **Measured 2026-10-24** on a restored copy of the dev database (3 projects, 13k issues, 503k histories, 77k worklogs): after `VACUUM (FULL)` raw 526 -> 422 MB (-20 %), the normalized tables
  identical to a REPROCESS by the untrimmed code. **Risk (irreversible):** getting dropped data back takes a drop-list code change, then a PURGE and a full re-sync - the ISSUES stream only re-reads issues updated
  inside its window, changelogs are fetched only while `changelog_synced_at` is stale, and a PURGE plus re-sync with the current code strips again. **At production scale** (WHY 41, 2026-10-25: 64k issues, 11 data sources, trimmed and compacted) the database is 3.0 GB, 47 MB per 1,000 issues: raw 1,878 MB (`raw.jira_changelogs` 1.33 GB, 2.41M rows), norm 611, metrics 495 MB.
- **`raw.jira_worklogs`** — PK `(connection_id, worklog_id)`, one row per Jira worklog —
  **in-scope issues only** (plan §0 A1): this table only ever gets a row the WORKLOGS stream has
  already checked against a known, non-tombstoned `raw.jira_issues` row for THIS connection
  (`JiraRawStore.knownInScopeIssueIds`) — everything else is dropped before any write. Same
  sha256 diff/tombstone rule as `raw.jira_issues`/`raw.jira_entities` (`JiraRawStore.upsertWorklog`/`upsertWorklogs`/
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

`norm.*` (v0.2.0 plan §0 A3/§4/§8, plan commit 8a, `norm/WorkItemStore.kt` — the tables and the one facade, delegating to `norm/WorkItemWriter.kt` (PROCESS write path), `NormReferenceStore.kt`, `NormDerivationReads.kt`, `NormProfileReads.kt`, `NormInspectorReads.kt`, `NormPickerReads.kt` and `NormPurge.kt`, one concern per file; a new `norm` read/write goes in its concern's file plus a one-line delegation on the facade) is the FIRST schema
outside `raw`/`public` — the connector-agnostic facts every connector's PROCESS step rebuilds a
work item's rows into, one issue at a time, keyed the same way `raw.jira_issues` is
`(connection_id, issue_id)`. `norm/Tiling.kt`/`norm/Normalization.kt` build the in-memory shape;
`jira/JiraNormalizer.kt` is the Jira-specific parser that feeds it; `jira/JiraProcessStream.kt` is
the PROCESS stream that drives the write. See `.claude/docs/ingestion.md` "Normalized layer" for
the tiling invariants and stream mechanics — this section is the schema/persistence side only.

**Planner statistics of `norm` (maintenance, build-times WHY 60).** `norm.*` is filled by PROCESS in many small committed page
transactions, and nothing else maintains its statistics except autovacuum, which analyzes a table only after ~10 % of it changed and
its naptime passed: a DERIVE that starts right behind a second connection's PROCESS plans that connection's rows from statistics that
describe the first alone. So a PROCESS that wrote rows into the tables DERIVE joins ends with a bounded `ANALYZE` in its own
short transaction when ANY of: a table was never analyzed (`reltuples < 0`); the tables' summed `pg_stat_user_tables.n_mod_since_analyze`
is at least 10 % of their `pg_class.reltuples` sum (accumulating many small SYNCs and a failed attempt's committed rows); this connection
is absent from `connection_id`'s `pg_stats.most_common_vals` on the first table (a tiny connection the sample misses pays about 0.35 s
per SYNC, accepted) - `norm/NormAnalyze.kt` `analyzeIfMaterial`, which `check`s that no transaction is open so its `SET LOCAL` cannot
leak into one; `SET LOCAL lock_timeout = 5000` plus a 30 s `queryTimeout`, never a `SET LOCAL statement_timeout`; a failure is
one WARN with the SQLSTATE when there is one, and the job succeeds). Jira: `work_items`, `work_item_worklogs`, `work_item_field_intervals`; GitLab: `code_commits`,
`code_merge_requests`, `code_mr_events`. The other `norm` tables are left to autovacuum (no statement DERIVE or the
PROFILE runs over them plans badly on a stale estimate, or they are small and rebuilt wholesale). A new table that a DERIVE or PROFILE statement joins by `connection_id` goes into
the matching list (`NORM_WORK_ITEM_ANALYZED_TABLES` / `NORM_CODE_ANALYZED_TABLES`); the `metrics` side is `MetricsAnalyzer`.

- **`norm.work_items`** — PK `(connection_id, issue_id)`. Every column is the CURRENT snapshot
  only — history lives in the interval/change tables below, never here. `status_category` is one
  of `TODO|IN_PROGRESS|DONE|UNKNOWN` (Kotlin-enum-whitelisted, not CHECK-constrained — the
  `raw.jira_entities.kind` idiom). `anomalies` is a JSONB array of anomaly codes flagged while
  tiling this issue's status history — flagged only, never "fixed" (see ingestion.md). `deleted_at`/
  `moved_out_at` mirror `raw.jira_issues`' own tombstones. `processed_at`/`processing_version` are
  this row's own bookkeeping, separate from `raw.jira_issues.processed_at`/`processing_version` (the
  raw row's own processing pointer) — both move together, written in the SAME transaction
  (`WorkItemStore.replaceWorkItems` + `JiraRawStore.markProcessedBatch`, inside
  `JiraProcessStream`'s per-page `context.transaction { }`).
- **`norm.work_item_status_intervals`** — one row per tiled status interval, PK-less surrogate
  `id SERIAL` (`BIGINT` since V33), unique on `(connection_id, issue_id, seq)`. The first interval (`seq = 1`) always
  starts at `work_items.created_at` with `source = 'CREATED'`; every later one is `'CHANGE'`.
  `to_at IS NULL` marks the one interval that is always open. `idx_norm_work_item_status_intervals_issue`
  (`connection_id, issue_id`) backs the per-issue replay this table's own REPLACE and the pipeline
  test's invariant sweep both read.
- **`norm.work_item_field_intervals`** — the same tiling shape for `ASSIGNEE`/`SPRINT`/`FLAGGED`
  (`TrackedField`, Kotlin-enum-whitelisted, not CHECK-constrained), unique on
  `(connection_id, issue_id, field, seq)`. SPRINT's `value_id` is the LAST sprint id of a
  (possibly multi-valued) carry-over set; `value_text` is the comma-joined sprint names Jira's own
  changelog `toString` already carries — never recomputed from ids. `value_text` is `TEXT` since V18
  (V13 created it `VARCHAR(500)`): the sprint name list is unbounded Jira text, and an issue carried
  through ~15+ sprints overflowed 500 chars — Exposed's client-side varchar length check threw before
  any SQL ran, failing its PROCESS write forever
  (`NormalizationPipelineTest` "PROCESS stores a sprint name list longer than 500 characters in full").
  V26 (`V26__drop_redundant_field_interval_index.sql`) dropped the former `idx_norm_work_item_field_intervals_issue`
  (`connection_id, issue_id, field`): a strict prefix of the unique key, so the unique index serves every per-issue/per-field replay it served (~1.7 MB saved).
- **`norm.work_item_field_changes`** — every tracked changelog item kept VERBATIM (never tiled):
  status, assignee, Sprint, Flagged, Rank, priority, resolution, issuetype, project, Key and story
  points. This is the only normalized record for the fields with no interval table of their own
  (priority, resolution, issuetype, project, Key, story points, Rank). `idx_norm_work_item_field_changes_issue`
  (`connection_id, issue_id`) backs the per-issue read.
- **`norm.work_item_worklogs`** — PK `(connection_id, worklog_id)`, mirrored from
  `raw.jira_worklogs` (already in-scope-filtered, A1) — PROCESS's own copy, so a metrics query
  never has to join back into `raw` (except the backlog report's touch read of `raw.jira_changelogs`, A38). `idx_norm_work_item_worklogs_issue` backs the per-issue read.
- **Reference rows** (`norm.statuses`, `norm.people`, `norm.boards`, `norm.board_columns`,
  `norm.sprints`) — rebuilt WHOLESALE per connection on every PROCESS run
  (`WorkItemStore.replaceStatuses`/`replacePeople`/`replaceBoards`/`replaceSprints`), never
  diffed/upserted row-by-row like `raw.jira_entities`, since PROCESS already reads the full current
  `raw.jira_entities` set every time it runs. `norm.board_columns.status_ids` is a JSONB array of
  status ids rather than a join table, since it is only ever read whole. Names, display names and
  e-mail are `TEXT` since V19 (Jira free text, no limit worth enforcing); the bounded keys/enums
  (`status_id`, `account_id`, `board_type`, `project_key`, `state`) stay `VARCHAR` and an overflowing
  row is skipped and logged by the rebuild (`.claude/docs/ingestion.md` "Reference-row robustness").

**REPLACE semantics (per page).** `WorkItemStore.replaceWorkItems` is one transaction per PAGE of up
to 50 issues (plan §8 step 5; `replaceWorkItem` is the one-issue call of the same code — the fallback
path and the fixtures): delete `norm.work_item_status_intervals`/`_field_intervals`/`_field_changes`/
`_worklogs` for the page's `(connection_id, issue_id IN (…))`, insert the freshly tiled rows (one
`insertRows` call per table — `infra/db/MultiRowInsert.kt`, one multi-row `INSERT … VALUES` per chunk, generated serial ids never returned, `build-times.md` WHY 3 follow-up;
`work_item_field_changes.seq` stays 1-based PER ISSUE), then `upsertRows` `norm.work_items` (`MultiRowInsert.kt`, one multi-row statement per chunk) on its
PK (`ON CONFLICT (connection_id, issue_id) DO UPDATE` — every non-key column is written either way,
the same row the old select-then-insert/update produced) — all inside the SAME transaction
`JiraProcessStream` also uses for `JiraRawStore.markProcessedBatch`, so a crash mid-page never leaves
a half-written normalized row or a raw row pointing at rows that were never written. A DB error in
the page write aborts the whole page transaction; `JiraProcessStream` then retries the page issue by
issue (each in its own transaction, via `replaceWorkItem`/`markProcessed`), so one bad row never
blocks its page (ingestion.md "PROCESS batching, failure isolation and progress").

**`PROCESSING_VERSION`** (`norm/Normalization.kt`, currently `3` — bumped from `1` to `2` by V14, "The
normalized layer gaps (V14)" below, and to `3` by A47's name-only issue-key change item) — bump it on ANY change to the tiling/write-shape rules.
`JiraRawStore.issuesToProcess` claims a raw issue whose `processing_version IS DISTINCT FROM` the
current constant (or is `NULL`, or `needs_processing` is flagged), so a version bump reprocesses
every issue automatically on the next PROCESS pass — proven by `NormalizationPipelineTest`'s "a
processing_version mismatch makes an issue eligible for the next PROCESS pass".

**PURGE of `norm.*` rows (plan §0 A2).** `WorkItemStore.purgeWorkItemsBatch`/
`purgeStatusIntervalsBatch`/`purgeFieldIntervalsBatch`/`purgeFieldChangesBatch`/
`purgeWorklogsBatch` (500 rows per call, `NORM_PURGE_BATCH_SIZE`, mirroring
`JIRA_PURGE_BATCH_SIZE`) delete one connection's rows in batches; the small reference tables are
cleared outright (`purgeReferenceRows`, no batching needed — they are already rebuilt wholesale).
`WorkItemStore.purgeAll` (an extension function in `norm/NormPurge.kt`) drains field changes, field intervals, status
intervals, worklogs, then work items, in that order, before clearing the reference tables.
`JiraConnector.purgeSteps` runs `JiraRawStore.purgeAll` (the `raw.*` tables) THEN
`WorkItemStore.purgeAll` (the `norm.*` tables) as its two connector-owned PURGE steps.

### The normalized layer gaps (V14)

`V14__norm_phase3_gaps.sql` (v0.3.0 M1 commit 2, `.claude/docs/domain-model.md` "Gaps in `norm`")
is purely additive — no existing V1–V13 file changes, no data migration, every new column
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
  revisit if a real tenant's row size becomes a problem; DERIVE no longer pays for it in memory — its read keeps only the
  configured field ids inside the SELECT, `metrics.md` "Memory (review round 2b)").
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
  `metrics/MetricsSettingsService.kt` (the table object `MetricsSettingsService.Settings` lives there; the eight
  per-connection config tables stay nested in `MetricsConfigService`). `time_zone` defaults to `'Europe/Warsaw'`, not UTC (main-session
  amendment A4 — the unit is Polish; an admin can change it). `hours_per_day` is a manual setting in
  v0.3.0 (A5); reading Jira's own time-tracking configuration was closed 2026-10-29 (the real data's whole-day worklogs
  match the setting, and the read would need a further Jira endpoint for no change).
  `config_revision` is the ONE revision the whole metrics layer is built against: bumped inside
  EVERY config mutation's own transaction — this row's own PUT, `metrics/TeamMembershipService.kt`'s
  create/update/delete (nested into the SAME transaction via `MetricsSettingsService.bumpRevision`,
  since both live in the `metrics` package — not a cross-feature read), and, from a later commit on,
  every per-connection config PUT (which DERIVEs only its own connection: `metrics.md` "DERIVE fan-out per
  config change").
- **`metrics.team_sprint_capacity` was LEGACY since V24 and is DROPPED by V34** (domain-model A32): nothing read or wrote it any more (the
  metrics-config API dropped `sprintCapacities`, DERIVE resolves capacity from V24's tables), so its Exposed object and its PURGE line went
  with the table. V15 and V24's seed (which reads it) are history and stay as applied; `CapacitySeedMigrationTest` recreates the table from
  V15's DDL inside its rolled-back transaction to keep proving V24's seed SELECT.
- **`metrics.status_stage_map`/`field_config`/`domain_map`/`board_team_map`/
  `activity_type_map`/`work_category_map`** (and `blocked_statuses` until V29 dropped it, A36) — the per-connection configuration
  tables the v0.3.0 plan's commit 4 (`GET/PUT /api/v1/data-sources/{id}/metrics-config`) reads and
  writes; created here (priority item 1 of commit 3) so no later migration needs to add them.
  `status_stage_map`'s `domain_key` column holds the per-domain overrides (`''` = every domain; written since the stage-override UI, `.claude/docs/metrics.md` "Per-domain stage overrides"). `board_team_map` carries `CONSTRAINT uq_metrics_board_team_map_team_id UNIQUE (team_id)` — D10's
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
(the effective-dated bridges; `task_domain` holds the EPICS' `issuekey` history too since A47 — it has no foreign key to `dim_task`, the
name stays; an epic's first row starts at instant 0 rather than at its `created_at`, and a row for a project the data source does not read carries the item's current domain, A47; no migration: a connection gets its epic rows at its next DERIVE; a reader that joins it to an item picks the side by the item's own table), `fact_task_delivery`/`fact_epic_delivery` (accumulating snapshots),
`fact_sprint_scope`/`fact_sprint`/`fact_sprint_snapshot`/`fact_worklog`/`fact_epic_plan` and
`agg_daily_wip`/`agg_daily_flow` (their table objects land in this commit; commits 8/9 add the
writers). Interval storage mirrors `metrics.team_membership`'s own precedent (V15): half-open
`valid_from BIGINT NOT NULL, valid_to BIGINT NULL` pairs, no `tstzrange` (no r2dbc-postgresql codec
for it). Every table is `connection_id`-scoped and rebuilt WHOLESALE per DERIVE run — delete then
insert, `MetricsStore` (the facade in `MetricsStore.kt`; the writes live in `MetricsDimWrites.kt`/`MetricsFactWrites.kt`/`MetricsSprintWrites.kt`, the table objects in `MetricsTables.kt`, the row shapes in `MetricsRows.kt`) splits each pair into a `deleteX`/`insertX` method so
`MetricsDeriver.kt` can delete ONCE up front and insert BATCH BY BATCH (`.claude/docs/metrics.md`
"The DERIVE run algorithm") — EXCEPT `dim_date` (global, reconciled by `MetricsStore.ensureDimDate`:
its own committed `inTopLevelSuspendTransaction` under the advisory lock `DIM_DATE_LOCK_KEY`, writing only rows that
are missing or differ from the calendar — `.claude/docs/metrics.md` "Calendar math")
and `fact_sprint_snapshot` (append-only, see below). **The `insertX` methods write through `infra/db/MultiRowInsert.kt`'s `insertRows`**
(`batchInsert`'s call shape, one multi-row `INSERT … VALUES` per chunk): `exposed-r2dbc`'s `batchInsert` executes every row as its own bound
statement (~0.16-0.19 ms a row, 10x the multi-row cost — `.claude/docs/build-times.md` WHY 3). **The repo has exactly four PostgreSQL advisory locks, all transaction-scoped.** `DIM_DATE_LOCK_KEY` (`MetricsDimDateStore.kt`, the ASCII bytes of
"FlowDate", the single-`bigint` form `pg_advisory_xact_lock(key)`) and `ensureDimDate`, the only
`inTopLevelSuspendTransaction` caller (a write that commits while its caller's transaction is still open); no other code may take that key.
The second is `SyncJobsService.claim`'s per-connection try-lock, `pg_try_advisory_xact_lock(CLAIM_LOCK_NAMESPACE, connectionId)`
(`ingest/SyncJobLeases.kt`, namespace = the ASCII bytes of "SYNC" as an `int`; the two-`int` form is a separate key space from the `bigint` one, so the two cannot
collide) — `.claude/docs/ingestion.md` "Claiming". The third is `DATA_SOURCE_SCOPE_LOCK_KEY` (`ingest/DataSourceScope.kt`, the ASCII bytes of
"FlowSite", the single-`bigint` form `pg_advisory_xact_lock(key)`, distinct from `DIM_DATE_LOCK_KEY`): `DataSourceService.create`/`update` take it FIRST in their
transaction (before any row lock) when the scope rules are enforced, so the "one Jira site, no project in two active data sources" check
(domain-model invariant 13) reads a snapshot that includes every earlier writer's commit. A row lock cannot do the job: the rule spans
rows that may not exist yet (a create) and the `settings` jsonb of several rows, no constraint expresses it, and a `FOR UPDATE` on
the existing rows would miss a concurrent INSERT. The fourth is `TELEMETRY_LOCK_KEY` (`telemetry/TelemetryStore.kt`, the ASCII bytes of "FlowTele", the single-`bigint` try form `pg_try_advisory_xact_lock(key)`, distinct from the other `bigint` keys): the telemetry sampler's cluster section takes it FIRST in its transaction, so one process per sample interval does the cluster work (the hourly rollup, the prune and the cluster collectors) and the others skip at once rather than wait. A row lock cannot do the job: the question is "has anyone done this interval's work yet", and until someone has there is no row to lock. A new advisory lock needs its own constant, a line here and a reason a row lock cannot do the job.

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

V28 later added `fact_task_delivery.estimated_after_done` and `fact_epic_delivery.estimated_late`/`estimated_after_done` (see "Estimate after done (V28)").

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
  exclusion) via `DomainOwnerResolver.resolveOwnerTeamByDomain` (the one shared implementation,
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
  the fallback `isForeignWork` (`metrics/DeriveAttribution.kt`) applies when the task carries no sprint
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

Current migrations are `V1`–`V43`:

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
  disabled row (`ON CONFLICT DO NOTHING`); `FlowUserService.create` inserts the same row for every
  later user.
- `V6__create_teams` — flat teams (Lettuce's V2 minus `manager_id`, which V23 returns without the chain —
  no management chain by design): `teams` (`name` ≤100, `description` ≤500 nullable, epoch-millis `created_at`/
  `updated_at`, soft-delete) with the partial unique index `uq_teams_name_active` over
  `LOWER(name)` active rows (a deleted team frees its name, no case twins), and `team_members` — a
  **hard-delete join** (`team_id` CASCADE, `user_id` CASCADE, composite PK, an index on `user_id`
  for the "my teams" filter): no history worth keeping, wholesale add/remove per row; a
  soft-deleted user keeps the row (reads join `users` and flag it `deleted`), a soft-deleted team
  keeps its roster for the record (the whole `team_members` table was dropped by V30, below). `SERIAL`/`INTEGER` ids with a `BIGINT` FK to `users` (the V1
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
- `V18__widen_field_interval_value_text` — `norm.work_item_field_intervals.value_text` `VARCHAR(500)` →
  `TEXT` (see "The normalized layer (V13)" above): SPRINT's comma-joined name list is unbounded Jira
  text. A varchar→text change is binary-coercible (catalog-only, no table rewrite), V13's bytes stay
  immutable, no data migration, no reader changes (`WorkItemStore.FieldIntervals.valueText` is now
  `text(...)`); `MigrationChecksumTest` pins it.
- `V19__widen_reference_name_columns` — every Jira-supplied free-text NAME column `VARCHAR(n)` → `TEXT`:
  `norm.statuses.name`, `norm.people.display_name`/`email`, `norm.boards.name`, `norm.board_columns.name`,
  `norm.sprints.name`; the per-issue copies `norm.work_items.issue_type`/`status_name`/`resolution`/`priority` and
  `norm.work_item_status_intervals.status_name`; the changelog field name
  `norm.work_item_field_changes.field` (a custom field's DISPLAY name, up to 255 characters); DERIVE's copies
  `metrics.dim_sprint.name`, `metrics.dim_task.issue_type`/`activity_type`, `metrics.fact_task_delivery.activity_type`,
  `metrics.fact_worklog.activity_type`; and the metrics config's `metrics.activity_type_map.issue_type`/`activity_type`
  and `metrics.work_category_map.value_id`/`value_name` (a primitive-valued work-category field's value text IS its id)
  — 21 columns in all (see "The normalized layer (V13)" above and `.claude/docs/ingestion.md` "Reference-row robustness"): the reference rebuild runs
  outside PROCESS's per-issue bad-row classifier, so an over-long name used to fail every run. Same
  binary-coercible, catalog-only change as V18: PostgreSQL rewrites neither the table nor any index — verified
  on PostgreSQL 18 (`pg_class.relfilenode` of the table, its primary key and a plain btree unchanged across the
  ALTER); the only indexes on these columns are the primary keys of `activity_type_map` and `work_category_map`,
  reused as-is. `norm.work_item_field_intervals.field` (an internal tag) stays `VARCHAR(20)`. V13's/V15's/V16's
  bytes stay immutable, no data migration, no reader changes; `MigrationChecksumTest` pins it and
  `ProcessReferenceRowsTest` pins the column types (identifiers and keys stay `VARCHAR`).
- `V20__widen_jira_entity_id` — `raw.jira_entities.entity_id` `VARCHAR(50)` → `VARCHAR(255)`: the REFERENCE
  stream stores a `USER` under its `accountId`, and the first real tenant had a 76-character one (likely a Jira
  Service Management customer, `qm:<uuid>:<uuid>`) that failed every SYNC at the USER step. Raising a `VARCHAR`
  limit is the same catalog-only change as V18/V19 (neither the table nor its primary key index is rewritten);
  every column an accountId is copied into is already `VARCHAR(100)`. `MigrationChecksumTest` pins it.
- `V21__reconcile_backoff` — `source_connections` gains `reconcile_failures INTEGER NOT NULL DEFAULT 0` and
  `next_reconcile_at BIGINT` (nullable, epoch millis): a failing RECONCILE's back-off, the RECONCILE counterpart of
  `consecutive_failures`/`next_sync_at`. Only a SUCCESSFUL reconcile stamps `last_reconcile_at`, so a failed one stayed
  due and was re-enqueued on every scheduler tick (113 failed jobs in under 30 minutes on the first real tenant).
  Additive (a constant-default `NOT NULL` column is catalog-only on PostgreSQL 11+); neither column is exposed by the
  API. See `.claude/docs/ingestion.md` "Scheduling". `MigrationChecksumTest` pins it.
- `V22__waiting_stage` — the 4th status stage `WAITING` (domain-model A30): the stage `CHECK` of five columns is dropped
  and re-added with `'WAITING'` in the list — `metrics.status_stage_map.stage` (V15: `NOT_STARTED`/`IN_PROGRESS`/`WAITING`/
  `DONE`) and, with `UNMAPPED` kept, `metrics.dim_epic.current_stage`, `metrics.item_stage.stage`,
  `metrics.fact_task_delivery.current_stage` and `metrics.agg_daily_wip.stage` (V16). The inline `CHECK`s carry
  PostgreSQL's auto-generated names `<table>_<column>_check` (`status_stage_map_stage_check`, `dim_epic_current_stage_check`,
  `item_stage_stage_check`, `fact_task_delivery_current_stage_check`, `agg_daily_wip_stage_check`, read off a migrated
  database). A pure widening: no row changes, the re-add scans each table once; with the default configuration no row is
  `WAITING` (no status ever defaults to it), so the fixture digests do not move. `MigrationChecksumTest` pins it.
- `V23__team_manager` — `teams.manager_user_id BIGINT NULL REFERENCES users(id)` (`users.id` is `BIGSERIAL`) + an index:
  a team's optional manager, a Flow login, at most one per team, one user may manage several teams (no history, no chain).
  Only the manager or an ADMIN edits the team's capacity (V24's routes, guard `requireTeamManagerOrAdmin`). A soft-deleted
  user's id may linger in the column: every reader (`TeamService.read`/`list`/`isActiveManager`) resolves the manager
  through ACTIVE users and reads a deleted one as "no manager" — the `metrics.domain_map.owner_team_id` idiom
  (soft-delete-safe by construction, no cleanup write on user delete). Additive and nullable: no backfill, no digest moves.
  `MigrationChecksumTest` pins it.
- `V24__capacity_management` — see "Capacity management (V24)" below: `metrics.team_capacity_defaults`,
  `metrics.sprint_calendars`, the widened `dim_sprint.capacity_source` and the legacy-figure seed. `MigrationChecksumTest` pins it.
- `V25__public_holidays` — see "Public holidays (V25)" below: `metrics.public_holidays`, the Polish seed 2017–2030, the carry-over of
  the old `metrics.settings.holidays` and its drop, the revision bump and the DERIVE fan-out. `MigrationChecksumTest` pins it.
- `V26__drop_redundant_field_interval_index` — drops `norm.idx_norm_work_item_field_intervals_issue (connection_id, issue_id, field)`,
  a strict prefix of the table's unique key `(connection_id, issue_id, field, seq)`, which serves every query it served (~1.7 MB on
  real data). No data change, no digest moves. `MigrationChecksumTest` pins it.
- `V27__sync_jobs_not_before` — `sync_jobs.not_before BIGINT NULL` (epoch millis, the earliest time a PENDING job may be claimed; null =
  due at once). Backs the DERIVE debounce (`.claude/docs/ingestion.md` "Debounced DERIVE (V27)"). Nullable, no default, no backfill — a
  metadata-only change, no data rewrite, no digest moves. `idx_sync_jobs_claimable` is unchanged: the claim's `not_before` condition
  is a filter over the partial index's open rows. `MigrationChecksumTest` pins it.
- `V28__estimate_after_done` — see "Estimate after done (V28)" below: `estimated_after_done` on both delivery facts, `estimated_late` on
  the epic fact, the revision bump and the DERIVE fan-out. `MigrationChecksumTest` pins it.
- `V29__blocked_stage` — see "BLOCKED stage (V29)" below: the 5th status stage `BLOCKED` (domain-model A36), the conversion of every
  `metrics.blocked_statuses` tick into it, and `DROP TABLE metrics.blocked_statuses`.
- `V30__drop_team_members` (2026-10-17) — `DROP TABLE team_members` (its `idx_team_members_user_id` index and both FKs go with it): the V6
  Flow-login roster, inherited from Covenant, which nothing outside `teams/` ever read (no metric, report or permission; the team's
  capacity is edited by its manager, V23, or an ADMIN). A team's members are the dated Jira-account `metrics.team_membership` (V15, D1),
  untouched. A pure hard-delete join with no history worth keeping, so no data is carried over. Code removed in the same commit:
  `TeamService.TeamMembers`/`addMember`/`removeMember`, `POST`/`DELETE /api/v1/teams/{id}/members/{userId}`, `TeamCreateRequest.memberIds`,
  `TeamResponse.members`, `TeamListItem.memberCount`, the `memberId` list filter and the `team.member_added`/`team.member_removed` audit events.
  A pod of the previous release reads `team_members` on every team `GET`/list, so those answer `500` until it is replaced: roll the web and
  worker pods together (V25's posture). `MigrationChecksumTest` pins it.

- `V31__epic_estimate_gap_tolerance` — see "Epic estimate gap tolerance (V31)" below: `metrics.settings.epic_estimate_gap_tolerance_percent`.
- `V32__team_work_category_targets` — see "Team work-category targets (V32)" below: `metrics.team_work_category_targets`. Purely
  additive, no backfill, no digest moves. `MigrationChecksumTest` pins it.
- `V33__widen_rewritten_table_ids` (checkup 3, D9-3) — `ALTER TABLE … ALTER COLUMN id TYPE bigint` then `ALTER SEQUENCE … AS bigint` on the
  eleven `id SERIAL` tables PROCESS/DERIVE delete and re-insert whole on every run (`norm.work_item_status_intervals`, `…_field_intervals`,
  `…_field_changes`; `metrics.task_epic`, `task_domain`, `task_assignee`, `task_sprint`, `item_estimate`, `item_stage`, `item_blocked`,
  `fact_epic_plan`): a sequence is never reset by a DELETE, so the int32 ids ran toward 2^31 (dev: `item_stage_id_seq` 8.3 M after 26 days).
  Nothing references those ids, so no FK column widened; values and the `nextval` defaults are kept. Each table is rewritten under an
  ACCESS EXCLUSIVE lock: seconds at the dev tenant's size, run by Flyway at startup before the pod serves. Per table the column goes
  first, then the sequence (the sequence's lock blocks `nextval()`, so the reverse order could deadlock with a running old DERIVE). Their Exposed objects declare
  `long("id").autoIncrement()`. **Rule from here on:** an id in `raw`/`norm`/`metrics` is `BIGSERIAL`/`BIGINT` or a natural composite
  key, never `SERIAL`/`INTEGER` — `NarrowIdMigrationScanTest` fails a migration newer than V33 that creates one. The other `SERIAL` ids
  (`sync_jobs`, `derive_runs`, `teams`, `source_connections`, `team_membership`, `public_holidays`) are not rewritten wholesale and stay
  `INTEGER`. `MigrationChecksumTest` pins it.
  **The upgrade is stop-the-world** (like V25 and V29): an old PROCESS/DERIVE holds row locks on these tables and calls `nextval()`, which
  can deadlock with the migration (Flyway fails, the pod restarts). Every old web AND worker pod must be gone before the new release
  migrates: both Deployments are `strategy: Recreate` (no overlap within each), but they are two Deployments and both run Flyway, so the
  operator scales the other Deployment to 0 (or applies both together) before the first new pod starts.
- `V34__drop_team_sprint_capacity` (checkup 3, D3-5) — `DROP TABLE metrics.team_sprint_capacity` (legacy since V24, see above); irreversible.
  `MigrationChecksumTest` pins it. **The upgrade is stop-the-world** (like V25 and V29): an old worker's PURGE still deletes from the dropped
  table and fails until it is retried, so scale the other Deployment to 0 before the first new pod starts (same wording as V33 above).
- `V35__password_reset_tokens` (2D3) - the single-use self-service reset grants: `token_hash` (the SHA-256 hex DIGEST of the emailed
  token, the primary key - the raw 256-bit token exists only in the email and in confirmation memory), `user_id` (FK `users`, `ON DELETE
  CASCADE`), the account snapshot the link was issued against (`email` and `credential_revision` - a grant goes stale when either
  moves, `auth/PasswordResetService.kt`), `expires_at` (epoch millis), and indexes on `user_id` and `expires_at`. Purely additive (no
  stop-the-world). **Hard-delete table**, an expiring single-use credential, not history: confirming deletes every grant of the user,
  a failed delivery revokes its own, and every `issue` prunes expired rows (in its own transaction FIRST, preserving the
  user-before-grant lock order). `issue` and `complete` lock the active user row `FOR NO KEY UPDATE` before touching grants, and `complete`
  does the grant consumption, the password write (`flowusers` `updatePasswordInTransaction`, conditional on the captured revision) and
  the sibling deletion in ONE transaction - never compose two service transactions. `MigrationChecksumTest` pins it.
- `V37__source_connection_pause` - `source_connections` gains `limit_trips INTEGER NOT NULL DEFAULT 0` (consecutive SYNCs that stopped at a
  ceiling without moving any cursor), `paused_at BIGINT` and `pause_reason VARCHAR(100)` (both nullable; the pause that counter causes, a
  constant trip code `LIMIT_EXCEEDED`|`TIME_EXCEEDED`), with three CHECKs (`limit_trips >= 0`; `paused_at` and `pause_reason` null together; `pause_reason` in the two trip codes).
  Additive and catalog-only (a constant-default NOT NULL column, two nullable ones); existing rows read as "no trips, not paused"
  (`PauseMigrationTest` migrates a scratch database through V36 -> V37 over a legacy row). Written ONLY inside the transaction that closes
  a job (`SyncJobLeases.failTripped`, `recordSyncSuccess`) or by an ADMIN's resume (`resumeFromPauseRow`, the PUT's `clearPause`), all under the
  connection row's `FOR NO KEY UPDATE`; the scheduler's SYNC scan (not RECONCILE's) filters on `paused_at IS NULL` (`ingestion.md` "Ceiling trips and the pause").
- `V38__source_connection_reconcile_pause` - V37's RECONCILE twin: `source_connections` gains `reconcile_limit_trips INTEGER NOT NULL DEFAULT 0`
  (consecutive RECONCILE ceiling trips, counted whether or not they stored a change), `reconcile_paused_at BIGINT` and
  `reconcile_pause_reason VARCHAR(100)` (nullable, the pause that counter causes, `LIMIT_EXCEEDED`|`TIME_EXCEEDED`), with the same three CHECKs
  (`source_connections_reconcile_*_ck`). Additive and catalog-only; existing rows read as "no trips, not paused" (`PauseMigrationTest` runs
  V36 -> V37 -> V38 over a legacy row). Written ONLY in the transaction that closes a RECONCILE job (`SyncJobLeases.failTripped` ->
  `recordReconcileTrip`), by `recordReconcileSucceededRow` (reset) or by an ADMIN's resume (`resumeFromReconcilePauseRow`, the PUT's
  `clearReconcilePause`), under the connection row's `FOR NO KEY UPDATE`; the scheduler's RECONCILE scan (not SYNC's) filters on
  `reconcile_paused_at IS NULL`. `MigrationChecksumTest` pins it.
- `V39__history_horizon_months` - see "History horizon (V39)" below: `metrics.settings.history_horizon_months`.
- `V40__source_connection_kind_gitlab` - the GitLab connector kind (`.claude/docs/gitlab-integration.md` "The connector seam"):
  `source_connections.kind`'s CHECK widened from `('JIRA_CLOUD')` to `('JIRA_CLOUD', 'GITLAB')`. V8 declared it inline and unnamed, so
  PostgreSQL named it **`source_connections_kind_check`** (confirmed on the Testcontainers DB; `ConnectorSeamTest` reads it back from
  `pg_constraint`); V40 drops and re-adds it under that name (V16's constraint-swap pattern for `sync_jobs.kind`). **Schema-only**: no
  row is rewritten (every existing row is `JIRA_CLOUD` and satisfies the wider check), no new column, and nothing creates a `GITLAB` row
  before the GitLab data-source release, so an old pod never reads one. `MigrationChecksumTest` pins it. No cross-feature read or write
  is added: the list above is unchanged.
- `V41__gitlab_raw_store` - see "The GitLab raw store (V41)" below: `raw.gitlab_projects` and `raw.gitlab_users`.
- `V42__gitlab_commits_raw_store` - see "The GitLab commits raw store (V42)" below: `raw.gitlab_commits` and `raw.gitlab_commit_files`, and `raw.gitlab_projects.commits_head_sha` widened to `VARCHAR(64)`.
- `V43__gitlab_merge_requests_raw_store` - see "The GitLab merge-request raw store (V43)" below: `raw.gitlab_merge_requests` and `raw.gitlab_mr_events`.

The `users`/`teams` tables follow Toadie's dialect (`SERIAL`/`INTEGER` ids, epoch-millis `BIGINT`
timestamps, `marked_as_deleted` + partial unique indexes over active rows) and its idioms: a
CHECK only where the value drives behavior (`users.role`), a Kotlin enum as the whitelist
otherwise.

### Capacity management (V24)

`V24__capacity_management.sql` (domain-model A32; `metrics/SprintCalendarService.kt`, `metrics/CapacityKernels.kt`):

- **`metrics.team_capacity_defaults`** — PK `team_id` (FK `teams`), `default_capacity_md NUMERIC(8, 2)` nullable (`CHECK >= 0`; a
  row with a null figure = "no default", the same as no row), `updated_at`, `updated_by_user_id`. One MD figure per team —
  DERIVE's `TEAM_DEFAULT`. Per team, never per connection: PURGE does not touch it (a soft-deleted team's row is harmless, the
  FK stays valid; `forDerive` reads ACTIVE teams only, so a deleted team's figure stops feeding DERIVE with its GET/PUT 404).
- **`metrics.sprint_calendars`** — PK `(connection_id, sprint_id)`, `team_id` (the team it was saved for — a reader that finds
  another team here after a board re-map ignores the row, a write replaces it), `sprint_start_ms` (the sprint's `start_at` at
  save time: orders "the team's most recent EARLIER calendar", the coefficient carry-forward), `calendar JSONB`
  (`{teamCoefficient, days: [{day, enabled}], people: [{accountId, enabled, coefficient, cells: [{day, availability}]}]}`).
  **The document is read and written WHOLE** (the `norm.board_columns.status_ids` rule): one jsonb through `infra/db/Jsonb.kt`,
  written as `canonicalJson` of the canonicalised document (days by `day`, people by `accountId`, cells by `day`, two
  decimals), so the no-op PUT test is an equality on the decoded canonical document. A malformed stored document is an
  invariant violation and fails loudly. Hard-deleted by PURGE (`purgeConnection`, the step between the config drain and
  `MetricsStore.purgeAll`). No FK to `dim_sprint` (it is rebuilt wholesale by every DERIVE).
- **`metrics.dim_sprint.capacity_source`** widens `VARCHAR(10)` → `VARCHAR(20)` (`'TEAM_DEFAULT'` is 12 characters; catalog-only,
  the V18–V20 idiom) and its CHECK (auto-named `dim_sprint_capacity_source_check`, read off a migrated database) becomes
  `IN ('CALENDAR', 'TEAM_DEFAULT', 'DEFAULT', 'CONFIGURED')`. `CONFIGURED` stays accepted: a row written before V24 keeps it until
  its connection's next DERIVE rebuilds `dim_sprint`; the writer never emits it again.
- **The seed.** Each team's `TEAM_DEFAULT` is seeded from its most recent configured per-sprint figure in
  `metrics.team_sprint_capacity` (via `norm.sprints` → `board_team_map`, ACTIVE teams on ACTIVE connections only; a start_at tie takes
  the highest sprint id, a sprint without a start ranks last — `CapacitySeedMigrationTest` runs the seed SELECT over hand-built rows).
  `team_sprint_capacity` stayed unread afterwards and V34 dropped it. The seeded figure applies to EVERY sprint of
  that team after the next DERIVE, not only to the sprints that had a figure of their own: historical capacity flattens to it, and a
  snapshotted closed sprint whose capacity/load now differs from its frozen `fact_sprint_snapshot.capacity_md` shows in Data quality's
  snapshot-drift card (capped at 50 items), by design. A legacy 0 seeds 0 (a null load). On the dev database all legacy rows were
  20 MD for one team, so the seed reproduces them. Operator note: V24 does NOT bump `config_revision`, so the re-derive happens on the
  next scheduled DERIVE (or "Derive now").

The carry-forward read (`SprintCalendarService.carryForward`) orders `sprint_start_ms DESC, sprint_id DESC, connection_id ASC` over
the team's calendars on ACTIVE connections. V24 was edited once before reaching any persistent database (the seed's connection join,
the tie-break) and its pin re-taken; from here on it is immutable like every applied migration.

`MigrationChecksumTest` gains V24's pin.

### BLOCKED stage (V29)

`V29__blocked_stage.sql` (domain-model A36) replaces the per-connection "Blocked" status tick with a stage. In one
transaction, in this order:

1. **Widen the five stage CHECKs** (`status_stage_map_stage_check`, `dim_epic_current_stage_check`, `item_stage_stage_check`,
   `fact_task_delivery_current_stage_check`, `agg_daily_wip_stage_check`) with `'BLOCKED'` — drop, then `ADD ... NOT VALID` and `VALIDATE`, V22's
   two-step. Purely widening.
2. **Convert the ticks** (`metrics.blocked_statuses` is read here for the last time). Per ticked `(connection_id, status_id)`:
   (a) a global (`domain_key = ''`) `status_stage_map` row of IN_PROGRESS or WAITING becomes BLOCKED; (b) a per-domain override row of IN_PROGRESS
   or WAITING becomes BLOCKED (each row judged on its own stage); (c) with no global row and an IN_PROGRESS `norm.statuses` category a
   global BLOCKED row is inserted — with any other category nothing is; (d) a NOT_STARTED or DONE row is left
   alone and only the tick goes away (it only mattered after a reopen into that status). Note on (c): a connection holding ANY stored
   configuration (a tick counted) reads its stage map as stored, so a status with no row was effectively UNMAPPED, not category-defaulted;
   keeping the behaviour equivalent would have dropped the tick, but the insert deliberately gives a ticked IN_PROGRESS-category status its
   BLOCKED stage (the admin's tick said "this means blocked"). Flyway runs the file in one transaction, so the CHECK re-adds hold their
   locks until it commits.
3. **Re-derive, only if a tick existed**: the shared `metrics.settings.config_revision` is bumped and one scheduler-priority DERIVE
   (priority 10, `max_attempts` 3, `not_before` left NULL = due at once, `ON CONFLICT DO NOTHING` into an open one) is enqueued for each
   enabled, active connection that had a tick — V25's fan-out, narrowed. A disabled connection re-derives on its next enable.
4. **`DROP TABLE metrics.blocked_statuses`.** Code: `MetricsConfigService.BlockedStatuses`, the `blockedStatuses` request/response field and the
   `reports/DataQualityConfig.kt` existence probe (the cross-feature list above) are removed in the same commit; `ConfigMaps.blockedStatusIds`
   became the BLOCKED-stage spans of `blockedIntervals`.

The user's live install has no ticked status, so it converts nothing and bumps nothing. `BlockedStageMigrationTest` runs the migration's own
statements (extracted from the file) against a re-created `blocked_statuses` inside a rolled-back transaction and reads back every case above, the
revision bump and the fan-out (and that nothing moves without a tick). `MigrationChecksumTest` gains V29's pin.

- **The upgrade is stop-the-world** (like V25). A pod of the previous release reads `metrics.blocked_statuses` on every metrics-config GET
  and every DERIVE, so once V29 has dropped the table it fails, and it also throws on a `BLOCKED` stage row it does not know. Every old
  web AND worker pod must be gone before the new release migrates: both Deployments are `strategy: Recreate` (no overlap within each), but
  they are two Deployments and both run Flyway, so the operator scales the other Deployment to 0 (or applies both together) before the first
  new pod starts. A browser tab opened before the upgrade still sends `blockedStatuses`, and the strict-JSON
  `PUT /api/v1/data-sources/{id}/metrics-config` answers `400` until the tab is reloaded.

### Public holidays (V25)

`V25__public_holidays.sql` (Lettuce's V39/V41 dictionary, ported; domain-model A33; `metrics/PublicHolidayService.kt`):

- **`metrics.public_holidays`** — `id SERIAL` PK (Toadie's dialect), `holiday_date VARCHAR(10) NOT NULL UNIQUE` (ISO `YYYY-MM-DD`, lexicographic ==
  chronological), `name VARCHAR(100)`, `created_at` (epoch millis). **Hard-deleting, a documented exception to the soft-delete
  convention** (like Lettuce's registry): a holiday is a bare dated label that nothing references by foreign key, a removed row is
  simply re-added, and `UNIQUE (holiday_date)` must free the date again on delete (a soft-delete flag would need a partial unique
  index for no benefit). A duplicate date is SQLSTATE `23505` on `public_holidays_holiday_date_key`, named in
  `plugins/ErrorHandling.kt`'s `UNIQUE_CONSTRAINT_DETAILS` for the friendly `409`.
- **The seed** — the Polish statutory holidays 2017–2030 as LITERAL rows (13 a year before 2025, 14 from 2025 when Christmas Eve became statutory; 188 rows in all), proven by
  `PublicHolidaySeedTest` against an independent Meeus/Jones/Butcher generator (`PolishHolidays` in the tests).
- **The carry-over** — every date of the old free-form `metrics.settings.holidays` array survives as `'Holiday'` (the seed ran first,
  so its name wins on a shared date; only well-formed ISO dates are carried), then the column is DROPPED. The settings API lost its
  `holidays` field in the same release. `PublicHolidayMigrationTest` runs the carry-over and the fan-out statements, read from the file,
  in a rolled-back transaction.
- **The re-derive after the upgrade** — the seeded holidays change working days, so the migration does by hand what
  `MetricsSettingsService.bumpRevision()` does for a settings PUT: `config_revision + 1`, and one scheduler-priority `DERIVE`
  (`priority 10`, `max_attempts 3` = the `ingest.maxAttempts` default) per enabled, active connection, stamped with the connection's own
  `config_revision`, `ON CONFLICT DO NOTHING` (coalescing into an open DERIVE). The first DERIVE running at the new revision rewrites
  `dim_date` (`ensureDimDate`); DEFAULT capacity, the daily aggregates and every report follow from that DERIVE. Unlike V24, no wait for the next
  scheduled sync. A fresh database has no connection, so it only moves the revision.
- **Cross-feature access** — `metrics/MetricsSettingsService.readWithCalendar` reads `PublicHolidayService.PublicHolidays` (same
  package) in the settings read's own transaction: the ONE builder of `WorkingCalendar`. `PublicHolidayService` writes only its own
  table and calls `bumpRevision` (same package) inside the same transaction.

- **The upgrade is stop-the-world.** V25 `DROP COLUMN`s `metrics.settings.holidays`, and a pod of the previous release selects that
  column explicitly, so an old replica still running when the new one migrates starts failing its settings reads. Every old web AND
  worker pod must be gone before the new release migrates. `k8s/web-deployment.yaml` and `k8s/worker-deployment.yaml` are both
  `strategy: Recreate`, which guarantees no old/new overlap WITHIN each Deployment, but they are two Deployments and both run Flyway:
  rolling one while the other still runs the old image leaves exactly that overlap, so the operator scales the other Deployment to 0
  (or applies both together) before the first new pod starts. A browser tab opened before the upgrade also sends the dropped
  `holidays` key, and the strict-JSON `PUT /api/v1/metrics-settings` answers `400` until the tab is reloaded.

`MigrationChecksumTest` gains V25's pin.

### Estimate after done (V28)

`V28__estimate_after_done.sql` (domain-model A35; `metrics/DeriveTimelineKernels.kt`'s `estimateSnapshots`):

- **`metrics.fact_task_delivery.estimated_after_done BOOLEAN NOT NULL DEFAULT FALSE`** -- a DONE task with no estimate at start, none at done and
  one now (a backfill). `estimated_late` (V16) keeps its column but changes meaning for a done task: no estimate at start and one AT DONE.
  `estimate_changes_after_start` now counts only the timeline points in `(started_at, done_at]`.
- **`metrics.fact_epic_delivery.estimated_late` and `estimated_after_done`, both `BOOLEAN NOT NULL DEFAULT FALSE`** -- the epic fact had no
  `estimated_late` (V16 stored only the three own-estimate columns and the change count); report 5 re-derived it with the old rule. It now reads the kernel's
  answer from the column and the report-side derivation is deleted.
- **The re-derive after the upgrade.** The rule changes every stored estimate-adjustment number, and there is no metrics derive-version stamp
  (`derive_runs.processing_version` is `norm`'s `PROCESSING_VERSION` and nothing compares it), so the migration does by hand what
  `MetricsSettingsService.bumpRevision()` does (the V25 precedent): `config_revision + 1` and one scheduler-priority `DERIVE` per enabled,
  active connection (`priority 10`, `max_attempts 3`), `ON CONFLICT DO NOTHING`. Until that DERIVE finishes, existing rows read the new columns
  as `false` and keep the old `estimate_changes_after_start`/`estimated_late` values, so report 5 shows the old numbers (and 0 estimated-late
  epics) for a few minutes after the deploy -- the same window every DERIVE-semantics change has.
- **Rolling deploys.** An old (pre-V28) worker may still claim V28's enqueued DERIVE and write the old rule (and false new flags) until the
  next DERIVE runs on the new build -- the same exposure as V25.
- **Untouched**: `fact_sprint_snapshot` (D13, immutable, commitment-time estimates) and `fact_sprint`/`fact_sprint_scope` (estimates at entry/close).
  The migration is purely additive; no application column is dropped, so an old pod keeps working against the new schema.

`MigrationChecksumTest` gains V28's pin.

### Epic estimate gap tolerance (V31)

`V31__epic_estimate_gap_tolerance.sql`: **`metrics.settings.epic_estimate_gap_tolerance_percent INTEGER NOT NULL DEFAULT 25`** with
`CHECK (… >= 0 AND … <= 100)` -- the former constant `EPIC_ESTIMATE_GAP_TOLERANCE` of Data quality's "estimate differs from its tasks" finding,
now a global metrics setting (`MetricsSettingsResponse`/`Request.epicEstimateGapTolerancePercent`, validated 0..100 server-side too). It is read
at QUERY time over stored figures (`fact_epic_delivery.own_estimate_current_md` vs `child_sum_estimate_md`): no derived row depends on it, so the
migration bumps no `config_revision` and enqueues no DERIVE. Editing it later goes through `MetricsSettingsService.replace`, whose uniform rule
(any change bumps the revision and enqueues one debounced DERIVE per enabled connection) is kept -- the setting behaves like `min_sample_size`,
which is also read at query time and also bumps. Purely additive (an old pod ignores the column). `MigrationChecksumTest` gains V31's pin.

### Team work-category targets (V32)

`V32__team_work_category_targets.sql` (domain-model A43; `metrics/TeamWorkCategoryTarget{Service,Routes}.kt`, `metrics/TeamWorkCategoryTargets.kt`):
**`metrics.team_work_category_targets`** — PK `(team_id, category)`; `team_id INTEGER REFERENCES teams(id)` (the `teams.id` type, like
`team_capacity_defaults`), `category VARCHAR(100)` (a `metrics.work_category_map.category` label, or the literal `UNCATEGORIZED` = the
"No category" row), `target_percent INTEGER CHECK 0..100`, `updated_at BIGINT`, `updated_by_user_id BIGINT NULL REFERENCES users(id)`.

- **One current set per team**, replaced whole by `PUT /api/v1/teams/{id}/work-category-targets` (hard `DELETE` + multi-row insert in one
  transaction under `TeamService.Teams.lockActiveForWrite`, so two PUTs of a team serialize and a concurrent team delete cannot slip between). No
  history: the audit event names every change. An identical set writes nothing. An empty set = no targets configured (PUT `[]` clears).
- **The sum-to-100 rule is service validation, not a constraint** (a `CHECK` cannot span rows): `validateTeamWorkCategoryTargets` (shape and
  sum, run by the route and re-run by the service) plus the "key is a category the team can choose" check inside the locked transaction.
- **`ON DELETE`: none**, like every `teams(id)` reference (`team_capacity_defaults`, `board_team_map`, `team_membership`): teams are only ever
  soft-deleted, so a deleted team's rows are inert (its GET/PUT answer 404) and the FK stays valid; a future hard purge of teams must clear the
  table first. The user FK is likewise rule-less (users are soft-deleted; the column is informational).
- **Read at query time**: no derived row depends on it, so no `config_revision` bump and no DERIVE (the V31 posture). Per team, never per
  connection: PURGE does not touch it. A category that loses its mapping stays stored (flagged `mapped: false` on read, rejected on PUT, gone on
  the next replace).
- **Who may read/write** — the cross-feature list above: `TeamWorkCategoryTargetService` WRITES only its own table and READS
  `teams/TeamService.Teams` (the active check, `lockActiveForWrite`), `ingest/DataSourceService.Connections` (active connections only) and the
  per-connection config table of its own package, `MetricsConfigService.WorkCategoryMap` (`category`), to derive "the categories a team can
  choose": the distinct mapped labels of EVERY ACTIVE connection (joined to `Connections.active()`), plus `UNCATEGORIZED`. Board and domain
  ownership do not filter it: Costs attributes a team's worklogs by author team across all active connections. Report code (Costs part 4) may read the table at query time.

`MigrationChecksumTest` gains V32's pin.

### History horizon (V39)

`V39__history_horizon_months.sql` (domain-model A48): **`metrics.settings.history_horizon_months INTEGER NULL DEFAULT 24`** with
`CHECK (history_horizon_months IS NULL OR (… >= 6 AND … <= 120))` -- the global "work older than this is noise" setting
(`MetricsSettingsResponse`/`Request.historyHorizonMonths`, validated 6..120 or null server-side too; `metrics/HistoryHorizon.kt` holds the bounds and the pure
`historyHorizonOf`). **NULL = off**: the request key is required-nullable (an absent key is a `400`, an explicit `null` turns the horizon off), and
the column default 24 means an existing deployment wakes up at 24 months. The response also carries the server-computed `historyHorizonFrom` (today in
`time_zone` minus the months, ISO date; null when off), so no form does month arithmetic; it is derived on every read, never stored.

- **Read at query time**: no derived row depends on it, so the migration bumps no `config_revision` and enqueues no DERIVE (the V31 posture), and no
  `raw`/`norm`/`metrics` row is ever removed because of it -- the horizon hides, it does not purge (raising it brings the figures back, no re-derive).
  Editing it later goes through `MetricsSettingsService.replace`, whose uniform rule (any change bumps the revision and enqueues one debounced DERIVE per
  enabled connection) is kept: the DERIVE is unnecessary but harmless, the same documented precedent as V31/`min_sample_size`.
- **This release stores and edits the setting only**; the reports' read-time clamp and the data-source backfill floor follow.
- Purely additive (an old pod ignores the column). `MigrationChecksumTest` gains V39's pin.

### The GitLab raw store (V41)

`V41__gitlab_raw_store.sql` adds the GitLab connector's two raw tables in the `raw` schema (`gitlab/GitLabRawStore.kt` and its collaborators;
the design is `gitlab-integration.md` "Schema"). Both are keyed `(connection_id → public.source_connections, <id>)`, store a SLIM `payload JSONB`
(a projection, never the whole document: `gitlab/GitLabSlim.kt`) canonicalized by `canonicalJson` and hashed with `sha256Hex` like `raw.jira_*`, and
carry `first_seen_at` / `last_seen_at` / `changed_at` epoch millis.

- **`raw.gitlab_projects`**: `project_id`, `path_with_namespace` (≤1024), `group_path` / `group_id` (the CONFIGURED group the project was listed
  under, not its own subgroup), `default_branch`, `archived`, `moved_out_at` (not seen under any configured group in a COMPLETED pass; cleared when
  seen again), `deleted_at` (set by RECONCILE on a `GET /projects/:id` 404 of a moved-out project; `archived` is set true by RECONCILE on a 200 that says so; neither write touches the payload or hash, so the next listing resurrects the project through `changeProject`) and the COMMITS stream's `commits_head_sha` (CHAR(40) in V41, widened to
  VARCHAR(64) by V42) / `relist_requested` (PR 7; null/false until then).
- **`raw.gitlab_users`**: `user_id`, `username`, `name`, `state`, `bot`, `emails JSONB` (the sorted, distinct, lowercased union of the user's
  `email`/`commit_email`/`public_email` and the confirmed secondary emails - also inside the payload, so ONE hash covers both), `deleted_at` (not seen
  in a completed `/users` pass).
- **The diff rule is the Jira one**, lifted: `ingest/RawUpsertOutcome.kt`'s `classifyUpsert` (no stored row inserts, the same hash on a live row is
  unchanged, a tombstoned row seen again is resurrected, any other difference is a change). `GitLabRawWrites` loads a 100-row page with ONE
  `SELECT ... IN (...) FOR UPDATE` (keys sorted), inserts new rows in one multi-row `INSERT`, touches unchanged live rows with one `UPDATE ... last_seen_at`, and
  rewrites a changed or tombstoned row (payload, hash, `changed_at`, markers cleared). The sweeps (`markProjectsMovedOutNotSeenSince`,
  `markUsersDeletedNotSeenSince`) tombstone live rows with `last_seen_at` older than the pass start.
- **PURGE**: `GitLabRawPurge` deletes 500-row batches of each table per connection (`GitLabRawStore.purgeAll`, the connector's `PurgeStep`).
- Indexes `(connection_id, last_seen_at)` on both tables serve the sweeps and the users-freshness read (`oldestLiveUserSeenAt`). The telemetry size
  estimate picks both tables up by their `connection_id` column automatically. Purely additive: an old pod never reads them.
  `MigrationChecksumTest` pins it.

### The GitLab commits raw store (V42)

`V42__gitlab_commits_raw_store.sql` (`gitlab/GitLabCommitWrites.kt`, `GitLabCommitFileWrites.kt`, `GitLabCommitReads.kt`, the tables on `GitLabRawStore`;
design `gitlab-integration.md` "Streams") adds the default-branch commits of the live projects and the per-file line counts of each non-merge commit.
Privacy: no message body, committer, diff text or payload is stored - a commit's `title` (GitLab's first line, ≤255), author name/email (≤255, as sent;
canonicalised later), `authored_at`/`committed_at`, `parent_count`, `additions`/`deletions` from `stats`, and per file only the path, its lines and flags.

- **`raw.gitlab_commits`**: PK `(connection_id, project_id, sha)`, `sha VARCHAR(64)` (SHA-1 or SHA-256 repositories). **No payload and no hash**: a commit is
  immutable by SHA, so a re-listing only touches `last_seen_at` (`UNCHANGED`) and clears a tombstone (`RESURRECTED`, which sets `needs_processing` again
  and resets a `files_error = 'NOT_FOUND'` stamp - `files_fetched_at`/`files_error` back to NULL, since NOT_FOUND meant "gone" and seeing the commit
  again disproves it, so it re-enters the files backlog; its stored files and a `LIMIT_EXCEEDED` stamp stay); there is no `CHANGED` outcome. No FK to
  `raw.gitlab_projects` (as `raw.jira_issues` has none to its project). The page's key load is `SELECT ... FOR NO KEY UPDATE` (keys sorted), never
  `FOR UPDATE`: the key never changes and `raw.gitlab_commit_files` references these rows ("Row locks vs. FK locks").
- **The files-backlog states** (one column set, three states): `files_fetched_at` NULL = the `/diff` has not been read (the COMMIT FILES backlog; a merge
  commit, `parent_count > 1`, and a tombstoned one never enter it); set with `files_error` NULL = the file rows are complete (`files_changed` = the rows
  stored, `files_capped` = the 1,000-file cap cut them, in which case `files_changed` is 1,000 and PR 10 reads the flag, never a total); set with
  `files_error` (`LIMIT_EXCEEDED`: a diff page over `gitlab.maxResponseBytes`; `NOT_FOUND`: the commit is gone) = terminal, `files_changed` stays NULL and
  the commit's `stats` are all PROCESS has. `idx_raw_gitlab_commits_files_backlog` is the partial index of exactly the pending predicate, newest first.
- **`raw.gitlab_commit_files`**: PK `(connection_id, project_id, sha, path)`, `path` = GitLab's `new_path`, `old_path` for a rename only. **Both are cut
  by characters AND bytes** (`cutPath`, after the NUL replacement): at most 1,024 characters (the `VARCHAR(1024)` width) and at most 2,000 UTF-8 bytes,
  on a code-point boundary. The byte cap is what keeps the PK B-tree entry (connection 4 + project 8 + sha ≤64 + path ≤2,000, plus headers) under the
  ~2,704-byte limit, so no flush can fail on a long CJK path; 1,024 characters alone can reach 4,096 bytes. `files_changed` is the number of distinct
  paths AFTER the cut (the flush collapses the outcome's files by cut path, the first wins), so two long paths sharing the cut prefix are ONE row and
  ONE in the count, always equal to the rows stored.
  `additions`/`deletions` NULL when GitLab sent the file `too_large` or `collapsed` (a binary file counts 0/0), the new/deleted/renamed flags and
  `generated_file` (GitLab's own `linguist-generated` detection, free at fetch time and not recoverable at PROCESS without a refetch). The composite FK to
  `raw.gitlab_commits` is `ON DELETE CASCADE`; the `connection_id` column alone has no FK, the commit's already pins it. A flush
  (`flushCommitFiles`) inserts the files `ON CONFLICT DO NOTHING` and stamps their commits in ONE transaction, so a commit is never stamped without its files.
- **The tombstone sweep** (`tombstoneCommitsNotSeenSince`): at the end of a force-push re-list, the project's live commits with `committed_at >= since`
  (the oldest live commit when the re-list began, NOT the moving backfill floor) and `last_seen_at < passStartedAt` get `deleted_at = now` and
  `needs_processing = true`. A tombstone deletes nothing: the files stay until PURGE. RECONCILE sets `deleted_at` the same way: its `reconcile_commits` re-list runs the same sweep.
- **`needs_processing` / `processed_at` / `processing_version`** are the code-layer PROCESS's (`GitLabRawProcessStore` claims and marks them, V44), the `raw.jira_issues` shape; a files flush and a tombstone set
  `needs_processing` again. `idx_raw_gitlab_commits_needs_processing` serves its claim scan; `idx_raw_gitlab_commits_project_live` the per-project reads
  (`oldestLiveCommittedAt`, the sweep).
- **`raw.gitlab_projects.commits_head_sha`** is widened from `CHAR(40)` (V41, never written before this migration) to `VARCHAR(64)`: a SHA-256
  repository would otherwise truncate the head silently. `recordCommitsHead` writes the head and the `relist_requested` flag in one `UPDATE`.
- **`raw.gitlab_projects.relist_started_at`** (BIGINT, added by V42 as well, the table's epoch-millis convention) is the **re-list epoch**: `beginRelist` sets it when a
  RELIST begins (kept if already set, so a re-list that spans runs has ONE epoch) and `finishRelist` / `recordCommitsHead(..., relistRequested = false)` clear it. The sweep's
  candidates are the live commits not seen since it (`last_seen_at < relist_started_at`), so a candidate the sweep keeps (touched) never re-qualifies within the same re-list.
  The files backlog (`FILES_BACKLOG`) also requires an `EXISTS` live project on `raw.gitlab_projects`' primary key (`moved_out_at`/`deleted_at` null).
- **PURGE**: `GitLabRawPurge.purgeCommitsBatch` deletes batches of 25 COMMITS per connection (`GITLAB_PURGE_COMMITS_BATCH_SIZE`; a delete per project, so a
  SHA shared by two projects - a fork - stays exact) and the files go through the cascade. The batch counts commits, not rows, and each commit cascades up
  to 1,000 file rows (the files cap), so 25 bounds one statement to 25,000 cascaded rows where the 500 of the other tables would be up to 500,000; `GitLabRawStore.purgeAll` drains commits, then users, then projects. The telemetry size
  estimate picks both tables up by their `connection_id` column. Purely additive but for the widened column: an old pod never reads them.
  `MigrationChecksumTest` pins it.

### The GitLab merge-request raw store (V43)

`V43__gitlab_merge_requests_raw_store.sql` (`gitlab/GitLabMrWrites.kt`, `GitLabMrReads.kt`, `GitLabMrModels.kt`, the tables on `GitLabRawStore`; design
`gitlab-integration.md` "Streams") adds the merge requests of the configured groups' projects and their review activity. Privacy (decision 9, pinned by
`GitLabMrStoreTest` against `information_schema.columns`): the MR `title` (≤255), the two branch names (≤255), ids, times, states, counts and a slim
`payload`; never a description, a label, a URL, a name, an avatar, a pipeline or merge status, or the BODY of any note.

- **`raw.gitlab_merge_requests`**: PK `(connection_id, mr_id)` (GitLab's global id); unique `(connection_id, project_id, iid)` (the GraphQL answer and
  RECONCILE's per-project re-list read by iid). A merge request is MUTABLE, so it is hashed like `raw.gitlab_projects`: `payload` (the slim projection) is
  canonicalized and sha256'd, `diffPage` (`GitLabRawDiff.kt`, the ONE page rule of every hashed GitLab table) decides `INSERTED / UNCHANGED / CHANGED /
  RESURRECTED`, and a change sets `changed_at` and `needs_processing` and clears a `NOT_FOUND` or `FETCH_FAILED` `details_error` (seeing it again disproves "gone" and it is read afresh). The
  queryable columns duplicate the payload (`state`, `draft`, authors, times, branches, `title`, the three SHAs - stored only when a full 40/64-hex id -,
  `reviewer_ids` / `assignee_ids` as sorted distinct JSON arrays, `user_notes_count`). The page's key load is `SELECT ... FOR NO KEY UPDATE` (keys sorted),
  never `FOR UPDATE`: the key never changes and `raw.gitlab_mr_events` is inserted into by the details transactions ("Row locks vs. FK locks").
  No FK to `raw.gitlab_projects` (as `raw.gitlab_commits`, and `raw.jira_issues` to its project): the stream skips a merge request of an unstored
  project, and a project that moved out or was deleted keeps its merge requests. A change does not rewrite `project_id` / `iid` (a merge request never moves).
- **The stale-details rule** (one stamp for everything the details stream reads): `details_synced_at IS NULL OR details_synced_at < changed_at`, on a live
  merge request of a live project (`MR_DETAILS_BACKLOG`, the `FILES_BACKLOG` shape), newest `updated_at` first. The partial index
  `idx_raw_gitlab_mrs_details_backlog` has exactly that predicate; it contains no constant and the query renders no bind parameter for it (a
  parameter in a partial-index predicate defeats a cached generic plan, the V42 lesson; `GitLabMrStoreTest` pins the rendered SQL). The ONE write
  of a details read is `settleMrDetails` (events AND stamp in one transaction, the row locked `FOR NO KEY UPDATE` first, which also keeps a PURGE from
  deleting it mid-write): a complete read REPLACES the events, writes `notes_capped` and `additions` / `deletions` / `file_count` (GraphQL's
  `diffStatsSummary`; null = a gap, never a job failure) and clears `details_error`; a terminal error (`NOT_FOUND`: the notes answered 404;
  `LIMIT_EXCEEDED`: a page over the response ceiling; `FETCH_FAILED`: a read that kept failing `UPSTREAM_UNAVAILABLE`/`INVALID_RESPONSE`) writes NO events and leaves events, stats and cap as they were (the method refuses events
  with an error). The stamp is `details_synced_at = GREATEST(now, changed_at)`: a clock that did not pass the change (the same millisecond, or a worker
  clock behind the writer's) still leaves the backlog instead of re-selecting the row forever; a change made in the very millisecond of the stamp is
  not told apart (accepted). `mrDiffStatsMissing` counts the gaps of SETTLED rows only (`details_synced_at >= changed_at`): a changed row is pending, not a
  gap. The outcome type (`MrDetailsOutcome`) refuses the either/or violation.
- **The upsert never tombstones.** `deleted_at` is written by RECONCILE only, on a per-MR 404 confirmed by the project answering 200 before and after; a
  listing that omits a row (it is filtered by `updated_after`) and a notes 404 are no evidence. `retryMrDetails` (un-stamps a bounded set of `details_error` rows) and `writeMrDiffStats` (fills `additions`/`deletions`/`file_count`, stamp untouched) are RECONCILE's other two writes; all three are guarded on what they set, so a repeated pass is a no-op.
- **`raw.gitlab_mr_events`**: PK `(connection_id, mr_id, source, source_id)`, composite FK to the merge request `ON DELETE CASCADE`. `source` is `NOTE`
  (`source_id` = the note id), `APPROVAL` (the legacy `/approvals` object, `source_id` = the approver's user id, `event_at` NULL - the endpoint has no
  time) or `REVIEWER` (`/reviewers`, `action` = the state). A note is reduced to an action code (`COMMENT`, `DIFF_COMMENT`, `APPROVAL`, `UNAPPROVAL`,
  `CHANGES_REQUESTED`, `REVIEW_REQUESTED`, `REVIEW_REQUEST_REMOVED`, `COMMITS_ADDED`, `MERGED`, `CLOSED`, `REOPENED`, `DRAFT`, `READY`, `OTHER_SYSTEM`);
  `targets` (JSONB, ≤20 usernames of ≤255 characters) is kept for the two review-request actions of a NOTE only (`RawGitLabMrEvent` refuses it
  elsewhere) - the only record of WHO was asked and WHEN. The set is REPLACED on every complete details read (`settleMrDetails`: delete the merge request's
  rows, insert the fresh set `ON CONFLICT DO NOTHING`, in the transaction of the stamp; `project_id` is the merge request row's own), so no PK holds upstream
  text: `source` is a fixed code. `targets` are cut to their width first, then empties dropped and the rest made distinct.
- **Text cut** (`GitLabText.kt`'s `cutText`, which `cutPath` now uses): NUL replaced, then cut by code points (never half a surrogate pair, which
  `String.take` leaves) to the column width; a byte cap exists for a column inside a B-tree key and defaults to none.
- **The group id of a configured path** (`configuredGroupIds`, the MERGE REQUESTS listing's `/groups/:id`) is read from the LIVE stored projects only (not moved
  out, not deleted): a path whose group was deleted and re-created carries two ids across the rows, and the one on the most recently seen live row wins
  (ties: the greater id). A path with no live project is absent and lists nothing.
- **PURGE**: `GitLabRawPurge.purgeMergeRequestsBatch` deletes batches of 20 MERGE REQUESTS (`GITLAB_PURGE_MRS_BATCH_SIZE`); the batch counts merge requests,
  not rows, and each cascades up to 1,000 notes plus its approvers and reviewers (~1,100 rows), so 20 bounds one statement to ~22,000 cascaded rows
  (the commits' budget, ~25,000; 500 would be ~550,000). `GitLabRawStore.purgeAll` drains commits, merge requests, users, then projects.
- `needs_processing` / `processed_at` / `processing_version` are the code-layer PROCESS's (`GitLabRawProcessStore` claims and marks them, V44); `idx_raw_gitlab_mrs_needs_processing` serves its claim scan (a
  query on it must not bind the boolean as a parameter). The telemetry size estimate picks both tables up by their `connection_id` column. Purely
  additive: an old pod never reads them. `MigrationChecksumTest` pins it.

### The normalized code layer (V44)

`V44__norm_code_layer.sql` (`norm/CodeActivityStore.kt` - the five Exposed tables, the row shapes and the ONE facade `CodeActivityStoreKey`, delegating to
`CodeActivityWriter.kt` (the PROCESS write path), `CodeActivityReads.kt` (the profile aggregates and the row readers tests use) and `CodeActivityPurge.kt`;
`norm/CodeNormalization.kt` the codes and `CODE_PROCESSING_VERSION`; design `gitlab-integration.md` "PROCESS / PROFILE") adds the connector-agnostic facts
GitLab's PROCESS rebuilds from `raw.gitlab_*`. The `WorkItemStore` idiom (V13) throughout: every table carries `connection_id REFERENCES
public.source_connections(id)`, times are epoch-millis `BIGINT`, the codes (identity kind, event kind, details state) are Kotlin-enum-whitelisted and not
CHECK-constrained, and there is NO foreign key between two `norm` tables (a REPLACE deletes the children explicitly). Privacy (decision 9): no code, diff,
description or note body, and no key holds an email (decision 10).

- **`norm.code_projects`**: PK `(connection_id, project_id)`; path, group path, default branch, `archived`, and the `moved_out_at` / `deleted_at` markers
  mirrored from `raw.gitlab_projects`. **`norm.code_identities`**: PK `(connection_id, identity_key)`; the key is `u<GitLab user id>` (`kind` USER) or
  `e<first 32 hex of sha256(canonical email)>` (`kind` EMAIL, a commit author matching no GitLab user); `emails` (JSONB) keeps what `raw.gitlab_users` holds
  (USER) or the one canonical address (EMAIL), purged with the connection. A USER with a null `username` is a ghost (an id a fact names that has no users row).
  Both are REFERENCE rows, rebuilt WHOLESALE per connection on every PROCESS run (`replaceProjects` / `replaceIdentities`: delete the connection's rows,
  `insertRows` the current set), like `norm.statuses` / `norm.people`.
- **`norm.code_commits`**: PK `(connection_id, project_id, sha)` = the raw PK; `identity_key` the resolved author; `is_merge`; GitLab's own `additions` /
  `deletions` / `files` beside the **`additions_counted` / `deletions_counted` / `files_counted`** figures (after the path exclusions); `files_capped`;
  `deleted_at` mirrored; its own `processed_at` / `processing_version`. **A `_counted` null means UNKNOWN - files pending or terminally unavailable, a
  merge commit (no diff is read), or (additions / deletions only) an included file with unknown lines while GitLab's stats are missing - or the commit is
  `files_capped`, whose stats cover files that were never stored, so no remainder is credited - so `files_counted` is set but the counted lines are
  null - never zero.** `idx_norm_code_commits_identity (connection_id, identity_key, committed_at) WHERE deleted_at IS NULL`.
  **`norm.code_merge_requests`**: PK `(connection_id, mr_id)`, unique `idx_norm_code_mrs_project_iid (connection_id, project_id, iid)` and `idx_norm_code_mrs_author (connection_id, author_key, created_at) WHERE deleted_at IS NULL` (no index on `reviewer_keys` / `assignee_keys`: a report that needs one adds it); author / merge-user keys,
  `reviewer_keys` / `assignee_keys` (sorted JSONB arrays of `u<id>`), GitLab's diff stats (null = a gap; NOT exclusion-aware), `notes_capped`, and
  `details_state` (`READ` / `PENDING` / `UNREADABLE`: whether the events are current - a report reads the state, never "no events"). **`norm.code_mr_events`**:
  PK `(connection_id, mr_id, seq)` (no surrogate id; `seq` is the position in the CURRENT event set, renumbered on every PROCESS of the merge request - never reference an event by `(mr_id, seq)` across runs), `kind` (`COMMENT`, `DIFF_COMMENT`, `APPROVAL`, `REVIEW_REQUESTED`, `REVIEWED`, `CHANGES_REQUESTED`),
  `actor_key`, `event_at` (null = the source carries no time), `target_keys` (JSONB, set only for REVIEW_REQUESTED, and NULL there too when every named username failed to resolve); `idx_norm_code_mr_events_actor
  (connection_id, actor_key, event_at)`.
- **Facts are flagged, not rebuilt.** A commit / merge request is written when its raw row is `needs_processing` (or its `processing_version` differs from
  `CODE_PROCESSING_VERSION`): `replaceCommits` is ONE `upsertRows` on the raw PK writing every non-key column; `replaceMergeRequests` deletes the page's
  events, `insertRows` the fresh set and `upsertRows` the rows. A tombstone is MIRRORED in `deleted_at`, never deleted. Every write joins the caller's
  transaction (the PROCESS page transaction also holds the raw rows' marks). The fact writers skip empty input; the wholesale rebuilds (`replaceProjects`,
  `replaceIdentities`) always delete first, so an empty list empties them.
- **`CODE_PROCESSING_VERSION`** (`norm/CodeNormalization.kt`, `2` since V46) is separate from Jira's `PROCESSING_VERSION`: bump it on ANY change to the code layer's
  rules, and every raw GitLab commit / merge request is claimed again on the next pass with no migration.
- **PURGE**: `CodeActivityStore.purgeAll` (`norm/CodeActivityPurge.kt`) drains `code_mr_events` (batches of `NORM_PURGE_BATCH_SIZE` rows by their own
  `(mr_id, seq)` key), `code_merge_requests`, `code_commits` (batches by key), then clears `code_identities` and `code_projects` outright. The telemetry size
  estimate picks the tables up by their `connection_id` column. Purely additive: an old pod never reads them. `MigrationChecksumTest` pins it. (V46 adds a sixth table, `code_mr_issue_keys`, drained FIRST: "Merge request issue keys (V46)" below.)

### Merge request issue keys (V46)

`V46__norm_code_mr_issue_keys.sql` (`norm/CodeActivityStore.kt` `CodeMrIssueKeys`; the pure extractor `norm/IssueKeys.kt`; design `gitlab-integration.md` "Issue keys")
adds `norm.code_mr_issue_keys` and one Jira-side index. Same idiom as V44: `connection_id INTEGER REFERENCES public.source_connections(id)` (no `ON DELETE`), `mr_id
BIGINT`, and NO foreign key to `norm.code_merge_requests`.

- **`norm.code_mr_issue_keys`**: PK `(connection_id, mr_id, issue_key)`; `issue_key VARCHAR(20)` the UPPERCASE key; `in_title` / `in_branch` say where the merge
  request names it (a key in both is ONE row, both flags true; the target branch is never read). Every candidate the token rule admits is stored, with no
  knowledge of which Jira projects exist (`UTF-8`, `SHA-256` and a `v2-3` slug included): the reports scope a candidate to the known project keys at read time.
  `idx_norm_code_mr_issue_keys_key (connection_id, issue_key)` serves task -> merge requests. Merge requests only: commits are linked through their merge request.
- **REPLACED per merge request** like the events: `replaceMergeRequests` deletes the page's rows `(connection_id, mr_id IN (...))`, `insertRows` the fresh set
  and upserts the merge request rows, in the page transaction. A tombstoned merge request keeps its rows (the keys follow its title and branch; the reports read
  the live merge requests by joining `code_merge_requests`), exactly as its events do.
- **PURGE**: `purgeAll` drains `code_mr_issue_keys` FIRST (batches of `NORM_PURGE_BATCH_SIZE` rows by their own `(mr_id, issue_key)` key), then the events.
- **`CODE_PROCESSING_VERSION` 2**: the bump re-claims every raw commit and merge request on the next PROCESS (no migration, no refetch), which fills the table.
  The new table is not in `NORM_CODE_ANALYZED_TABLES` (the PROFILE's candidate count joins it to the live merge requests by its primary-key prefix `(connection_id, mr_id)`, an index probe whatever the estimate; no DERIVE statement reads it); a report that joins it at scale adds it there. **Rolling deploy:** a pre-1.45.0 worker's PURGE does not drain this table (nothing references it, so the purge still succeeds and leaves orphan key rows), so replace every worker before deleting a GitLab source.
- **`idx_norm_work_items_issue_key (connection_id, issue_key)`** on `norm.work_items` (V13 had no index on `issue_key`; the primary key is `(connection_id,
  issue_id)`): the key -> issue resolution. A plain `CREATE INDEX` (Flyway runs the migration in a transaction), built over the existing rows once.
- Purely additive: an old pod never reads either. `MigrationChecksumTest` pins it.

### Code identity links (V45)

`V45__code_identity_links.sql` (`identities/CodeIdentityLinkStore.kt`; design `gitlab-integration.md` "Identity") adds `public.code_identity_links`: an ADMIN's
decision about ONE GitLab identity of ONE instance. PK `(source_host, identity_key)`: `identity_key` (`VARCHAR(40)`) is the code layer's `u<id>` / `e<hash>` key
(`norm.code_identities`, V44) and `source_host` (`VARCHAR(300)`) the instance's authority - the lowercase host, plus `:port` when the base URL names one
(`sourceHostOf`, taken from the data source's canonical base URL). `decision` is `LINKED` / `IGNORED` / `BOT` (`CodeIdentityDecision`) and
`jira_account_id` (`VARCHAR(100)`, `norm.people.account_id`'s width) is non-null **exactly when LINKED**, and never empty; `decided_by_user_id` (`NOT NULL`, every decision
is made by an admin; `REFERENCES users(id)`, no `ON DELETE` rule: users are only ever soft-deleted, and an admin's departure must not unlink people) and `decided_at`
(epoch-millis `BIGINT`) say who and when. Four CHECKs enforce the shapes in SQL, whatever writes the row: `ck_code_identity_links_decision` (the closed vocabulary),
`ck_code_identity_links_key` (`identity_key ~ '^(u[0-9]{1,19}|e[0-9a-f]{32})$'`, what the code layer mints), `ck_code_identity_links_host` (`source_host <> ''` and
`= lower(source_host)`, what `sourceHostOf` mints) and `ck_code_identity_links_account` (`(decision = 'LINKED') = (jira_account_id IS NOT NULL)` and a present account
is non-empty: a LINKED row without an account, or an IGNORED / BOT row that still names one, is impossible). `idx_code_identity_links_account`
(`(source_host, jira_account_id) WHERE decision = 'LINKED'`) serves the resolver's `keysByAccount`, which filters on exactly those three.
There is no foreign key to `jira_account_id` (`norm.people` is per connection and rebuilt) - the service checks the account is known when it writes. **If a LINKED
account later vanishes from Jira** the link stays and still resolves; the list shows it with no display name, and a re-PUT of the same account is accepted (the
unknown-account check skips an account the stored link already names).

- **Host-keyed, no connection column, undated.** `norm.code_identities` is rebuilt wholesale per PROCESS run and drained by PURGE, and a data source can be
  deleted and created again for the same instance; a link points at the HOST, never at a row or a connection, so it **survives PURGE, a data-source
  delete and a re-created source** and applies again as soon as the identity rows are back. No purge step is registered (`JobHandlerRegistry` untouched).
  A decision whose identity no active data source on the host still has stays listed (as not seen) on the decided tabs of those sources and is removable there
  (an orphan is per INSTANCE: a key another active source on the host still has belongs to that source's page and is not listed on this one); with no
  active source on the host it waits, inert, until one is created. Proposals (an address matching a Jira person) are
  computed at read time and never stored; only decisions are rows, and only a LINKED decision attributes anything.
- **Readers and writers.** `identities/` only: `CodeIdentityLinkStore` is the single place that touches the table (`Links` is a query-only Exposed
  definition; `decided_by_user_id` is a plain `long` column there because `identities/` never imports `flowusers/`). The routes' service and
  `IdentityResolver` (the one entry point for the later reports) are its callers; nothing in `ingest/`, `norm/`, `jira/`, `gitlab/` references it. A row lock
  on a link is a plain `FOR UPDATE`: nothing references this table, so no long transaction holds key-share locks on it ("Row locks vs. FK locks"). The store's
  writes report what they wrote (`insertIfAbsent`/`update`: boolean; `insertIgnoringConflicts`: the keys inserted; `delete`: the row removed), and the PUT pattern is
  `find(forUpdate = true)`, else `insertIfAbsent`, and on a lost race `find(forUpdate = true)` + `update`, all in ONE transaction (`CodeIdentityLinkStore.find`).
- **Hard delete** (an exception listed in "Soft delete (convention)"): the deletable action is "this decision was wrong"; the audit trail carries who
  decided and who removed what.
- Purely additive: an old pod never reads it. `MigrationChecksumTest` pins it.

### Telemetry (V36)

`ops.telemetry_processes` and `ops.telemetry_samples` (V36; `telemetry/TelemetryStore.kt`, `.claude/docs/observability.md` "Telemetry")
are the first tables of the `ops` schema (created by this migration, the "no standalone create-schemas migration" rule). A process row is one
JVM run (`role`, `instance`, `started_at`, `last_seen`, `build_version`/`build_commit`, `processors`, `heap_max_bytes`, nullable
`container_limit_bytes`; unique on `(role, instance, started_at)`); `telemetry_samples` is ONE narrow table for every metric —
`(resolution, metric, ts, process_id, label)` is the primary key (`resolution` = the sample width in seconds: a raw row stores the sampler's real interval, 60..1800 and a divisor of 3600, an hourly rollup 3600 - the tier is `resolution < 3600`; the CHECK enforces it), `n`/`avg`/`max` the figures, so a metric is added in
`TelemetryMetrics.kt` with no migration. Epoch-millis `BIGINT` timestamps, `SERIAL` ids, the cluster section's rows (its marker today) attributed to
the process that took the lock. Writes go through `infra/db/MultiRowInsert.kt`'s `insertRowsIgnoringConflicts`; every statement runs under
the transaction's `queryTimeout`.

**The telemetry prune is a hard-delete exception**, the `sync_jobs` precedent ("Sync jobs and cursors (V9)"): samples are
regenerated, high-volume observations with no reader once old, so they are deleted, not soft-deleted — raw rows older than 7 days, hourly
rows older than 90, processes unseen for 90 days (their samples cascade, `ON DELETE CASCADE`; an index on `process_id` keeps that cascade
off a table scan). The prune runs inside the telemetry cluster transaction under `TELEMETRY_LOCK_KEY`, and a row exactly at its cutoff
stays. The tests own the schema: `TelemetryStoreTest`/`TelemetrySamplerTest` empty `ops` before each test (the suite starts no sampler).

### Soft delete (convention)

`users` and `teams` are **soft-deleted** — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `password_reset_tokens` (V35, expiring single-use credentials, not history), `user_disabled_features`, a pure flag join whose PUT is a wholesale replace,
`code_identity_links` (V45, "Code identity links (V45)": an admin's decision about one GitLab identity, undated and host-keyed; the deletable action is "this decision was wrong", the audit trail carries the removal - the `team_membership` justification below),
`metrics.public_holidays` (V25, "Public holidays (V25)") and `metrics.team_membership` (V15, D1) — a pure DATED
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
(reference implementation: `flowusers/FlowUserService.kt`):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit
   adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — declare the table `: UIntIdTable("…"), SoftDeletable` with
   `override val markedAsDeleted = bool("marked_as_deleted").default(false)`; the ONE `active()`
   predicate, `nowMillis()` and `lockActiveForWrite` come from `infra/db/SoftDelete.kt` (no
   private copies).
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get
   `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row
   select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`,
   returning the affected-row `Int`; guard `update` mutations the same way. The route maps
   `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`)
   and delete stays idempotent in effect — `FlowUserService.deleteGuarded` shows the shape, adding the
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

### Row locks vs. FK locks

PostgreSQL's foreign-key check takes `FOR KEY SHARE` on the REFERENCED row for every child insert and keeps it until the
inserting transaction ends. DERIVE rebuilds `metrics.*` in ONE long transaction whose inserts reference `teams` and
`source_connections` (`fact_*`, `dim_*`, `item_*` …), so it holds key-share locks on every team and connection row it
touched for its whole run (minutes on a large unit). `SELECT … FOR UPDATE` conflicts with `FOR KEY SHARE`: a team write that
locked its row with `FOR UPDATE` waited behind a running DERIVE, and — every save enqueuing another DERIVE — the capacity
page timed out.

The rule: **lock a row `FOR NO KEY UPDATE` unless the write changes its key.** That mode conflicts with itself and with
`UPDATE`/`DELETE` (writers of the row, e.g. a soft delete racing a membership insert or a calendar write, still serialize) but
NOT with `FOR KEY SHARE`, so DERIVE's FK locks never stall it. `lockActiveForWrite` (`infra/db/SoftDelete.kt`,
`FOR NO KEY UPDATE`; was `lockActiveForUpdate`), `TeamService.update`, `DataSourceService.update` and
`ScheduleBackoff.lockConnection` and `PasswordResetService.lockUser` (the active `users` row) follow it; none of them touches `id`, and the unique indexes on these tables are partial or
expression indexes (`uq_teams_name_active`, `uq_source_connections_name_active`, `uq_users_email_active`), which PostgreSQL does
not count as key columns — a rename is still a non-key update. Plain `FOR UPDATE` is for a row nothing references from a long
transaction (`users` is referenced only by columns no long transaction inserts — `updated_by_*`, `teams.manager_user_id`,
`user_disabled_features.user_id`, `sync_jobs.requested_by_user_id`, `password_reset_tokens.user_id` (short inserts only - `issue`), `code_identity_links.decided_by_user_id` (V45, one short insert or update per admin decision):
`FlowUserService.lockedActiveAdminCount`;
`raw.jira_*`, the `sync_jobs` rows), or for `SKIP LOCKED` claims. A new `FOR UPDATE` on an FK-referenced row needs that
justification in a comment. `RowLockVersusDeriveTest` pins both halves: a writer completes while another transaction holds
`FOR KEY SHARE` on the row, and a second `FOR NO KEY UPDATE` writer (the soft delete) still waits.

### Credential revision (V7)

`users.credential_revision` is an internal nonnegative BIGINT generation, initially zero. Every
password update/reset and conditional bootstrap rotation increments it atomically in SQL with the
hash update. It is carried in signed access and refresh tokens and pending MFA challenges; the per-request access
check (`TokenBlocklistService.accessVerdict`), refresh and MFA acceptance compare it with the current user row.
Soft delete and role changes do NOT bump it (the access check compares the stored role separately; a delete
is `user_gone`). It is not exposed in user DTOs. A password-reset grant (V35) captures it and goes stale when it moves.
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
`.claude/docs/authorization.md`). `reconcile_failures`/`next_reconcile_at` (V21, internal only, never in the
API) hold a failing RECONCILE's back-off — `ingestion.md` "Scheduling".

**`infra/db/Jsonb.kt`** — a repo-local `jsonb` column type, because `exposed-r2dbc` 1.5.0 ships no
JSON column type of its own. It needs no reflection into the raw `io.r2dbc.spi.Statement`: Exposed
already ships a `JsonColumnMarker` interface plus a `PostgresSpecificTypeMapper` that specifically
recognizes any `IColumnType` implementing it (binding a null via `Statement.bindNull(index,
Json::class)`, a `String` via `Statement.bind(index, Json.of(value))` —
`io.r2dbc.postgresql.codec.Json`) — implementing the marker IS the whole binding. Values are the
caller's JSON text verbatim; PostgreSQL's own `jsonb` storage reformats regardless (whitespace, key
order), so "round-trips" means the same VALUES survive, not the same bytes. **The one exception to "same values" is U+0000**:
PostgreSQL `jsonb` and `text` cannot store a NUL (`unsupported Unicode escape sequence`, SQLSTATE 22P05 for jsonb; `22021` for
text), and one in a pasted Jira description used to fail an ISSUES page — and so every retry of the SYNC — forever. Every
payload that goes through `canonicalJson` (`infra/json/CanonicalJson.kt`: the four raw Jira writers, `settings`, `profile`, the
sprint calendar) therefore has each U+0000 in a string value AND in an object key replaced with U+FFFD, before the text is
hashed, so the stored text and its sha256 agree (U+FFFD marks that something was there and cannot merge the tokens around it,
which dropping the character would). Every other character, control characters included, is kept; a payload without a NUL
canonicalizes byte-for-byte as before. A payload with a NUL could never be stored, so no stored hash moves. The helper
`String.replaceNul()` (same file) applies the same rule to free text bound for a `text` column that is not a canonical JSON
payload — today the two exception-message columns, `sync_jobs.error_detail` (`IngestWorker.onFailed`) and
`metrics.derive_runs.error_detail` (`DeriveRunLifecycle.markRunFailed`): a database exception's message can quote the offending
value, and a NUL there made the failure write itself fail, leaving the job RUNNING until its lease expired. Covered by
`JsonbColumnTest` (insert/select/update, null, nested objects/arrays, through Exposed R2DBC against
Testcontainers PG); the NUL rule by `CanonicalJsonTest` and `JiraRawStoreTest`.

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
order preserved, numbers/strings/unicode stable and idempotent, stable `sha256Hex`, the NUL → U+FFFD rule for values, array
elements and keys, other control characters kept).

`canonicalJson(JsonElement)` writes the tree straight to text (only each object's key list is sorted; no sorted copy of the tree is built),
so the raw writers canonicalize the tree the stream parsed instead of re-parsing its text (`jira-integration.md` "One tree a slot",
build-times WHY 48). Its output must stay byte-identical to the old sorted-copy implementation, since the stored text and sha256 are
compared against stored rows: `CanonicalJsonWriterEqualityTest` holds a verbatim copy of the old code as an oracle and compares text and
sha256 over every stub fixture body (whole documents, every item, raw and through the three payload trims), edge cases (escapes, unicode,
big/negative/float/exponent numbers, empty containers, NUL keys that collide) and random trees.

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
(`.claude/docs/metrics.md` "Sprint scope, facts and snapshots"/"Worklog cost facts"/"Epic plans
and PV"); the `agg_daily_wip` and `agg_daily_flow` writers landed with commit 9f
(`metrics/DeriveWipStep.kt`, `metrics/DeriveFlowStep.kt`).
