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
  acquirer, but that registry is empty today, so it is not yet a source of warmup either),
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

**Cross-feature table reads (the service-layer rule, inherited from Lettuce).** A feature service
MAY query another feature's Exposed table objects directly when the read must run **inside its
own transaction** (SQL joins, atomic snapshots) — the transaction boundary must be explicit rather
than relying on an unrelated service to preserve it. Exposed reuses an enclosing transaction for
nested `suspendTransaction` calls on the same database. Route handlers never touch tables
(services only). The reads in place: `TeamService` joins `UserService.Users` for the roster's
display fields and the active-member counts, and checks member ids against active users inside
the create/add transaction. List each new cross-feature read here as it lands — the list IS the
permission.

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
  below), joined by `raw.jira_raw_changelogs`/`raw.jira_raw_worklogs`/`raw.jira_reconcile_seen` in
  V11 once the CHANGELOGS/WORKLOGS/RECONCILE streams land (plan commit 7). A future GitLab
  connector adds `raw.gitlab_*` alongside these rather than inventing a fourth schema.
- **`norm`** — the neutral, source-agnostic layer every connector normalizes into (`work_items`,
  `sprints`, `boards`, …) — arrives with V12 (plan commit 8), upcoming as of this commit.
- **`metrics`** (not yet created) — phase 3's pre-aggregated tables get their own schema once that
  work starts (plan §4).

**How Exposed addresses a schema-qualified table.** No Exposed `Schema` object and no
`search_path` override are involved: `JiraRawStore.kt`'s `Issues`/`Entities` table objects simply
pass the dotted, schema-qualified name straight to the `Table(...)` constructor — e.g. `object
Issues : Table("raw.jira_issues")` — and Exposed/R2DBC resolve it as-is, including a cross-schema
FK reference back to `public.source_connections` (`reference("connection_id",
DataSourceService.Connections)`). The plan's fallback (setting `search_path` on the pooled
connection, with fully-qualified SQL in `exec` blocks, if qualified names misbehaved) was not
needed. `CREATE SCHEMA IF NOT EXISTS raw`/`norm` runs in the first migration that needs each schema
(V10 for `raw`; V12 will do the same for `norm`) — never a standalone "create schemas" migration.

### The Jira raw store (V10)

`raw.jira_issues` and `raw.jira_entities` (v0.2.0 plan §4/§7, `jira/JiraRawStore.kt`) are the FIRST
tables in the `raw` schema — the REFERENCE and ISSUES streams' target
(`jira/JiraReferenceStream.kt`, `jira/JiraIssuesStream.kt`).

- **`raw.jira_issues`** — PK `(connection_id, issue_id)` (the stable Jira numeric id; a project
  move only changes the `issue_key`/`project_id`/`project_key` columns, never the PK). Identity
  columns (`issue_key`, `project_id`, `project_key`, `issue_updated_at`) sit alongside `payload`
  (the canonicalized `search/jql` issue document, `infra/json/CanonicalJson.kt`) and its `sha256`.
  `changelog_synced_at`/`worklogs_synced_at` are NULL until the CHANGELOGS/WORKLOGS streams (plan
  commit 7, A1) populate them. `needs_processing`/`processed_at`/`processed_hash`/
  `processing_version` drive the PROCESS step (plan commit 8). `deleted_at`/`moved_out_at` are
  tombstone columns the RECONCILE stream (plan commit 7) sets; nothing in this commit's REFERENCE/
  ISSUES streams sets `moved_out_at` — a key/project change during an ISSUES page is just an
  ordinary column update, not a distinct "move" code path. Three partial indexes back the streams'
  own claim scans: `idx_raw_jira_issues_needs_processing` (PROCESS, commit 8),
  `idx_raw_jira_issues_stale_changelog` (CHANGELOGS, commit 7, `changelog_synced_at IS NULL AND
  deleted_at IS NULL`) and `idx_raw_jira_issues_stale_worklogs` (WORKLOGS, commit 7, A1's per-issue
  backfill scan, same shape).
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
  extension function) drains both tables by looping each batch call until it deletes zero rows.
  `JiraConnector.purgeSteps` wires this as the PURGE job's one connector-owned cleanup step.

Current migrations are `V1`–`V10`:

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

The `users`/`teams` tables follow Toadie's dialect (`SERIAL`/`INTEGER` ids, epoch-millis `BIGINT`
timestamps, `marked_as_deleted` + partial unique indexes over active rows) and its idioms: a
CHECK only where the value drives behavior (`users.role`), a Kotlin enum as the whitelist
otherwise.

### Soft delete (convention)

`users` and `teams` are **soft-deleted** — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace,
and `team_members`, a pure membership join) hard-delete — a new hard-delete table needs a
documented justification, exactly like Lettuce's exceptions list. `sync_jobs` (V9) is the one
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

Nothing remains on the persistence list today; the next subsystem (the `norm` schema and its V12
normalized layer, plan §0 A3/§8) arrives with its own paragraph here.
