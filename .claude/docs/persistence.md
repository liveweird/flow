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

Current migrations are `V1`–`V8`:

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

The `users`/`teams` tables follow Toadie's dialect (`SERIAL`/`INTEGER` ids, epoch-millis `BIGINT`
timestamps, `marked_as_deleted` + partial unique indexes over active rows) and its idioms: a
CHECK only where the value drives behavior (`users.role`), a Kotlin enum as the whitelist
otherwise.

### Soft delete (convention)

`users` and `teams` are **soft-deleted** — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace,
and `team_members`, a pure membership join) hard-delete — a new hard-delete table needs a
documented justification, exactly like Lettuce's exceptions list. To add soft-delete to a new
entity, follow the established pattern (reference implementation: `users/UserService.kt`):

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

### Not yet ported from Lettuce / Toadie / Covenant

Nothing remains on the persistence list today; the next subsystem (the Jira raw store and
incremental cursors, and the `raw`/`norm` PostgreSQL schemas — plan §0 A3, V10) arrives with its
own paragraph here.
