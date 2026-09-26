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

Current migrations are `V1`–`V7`:

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

### Not yet ported from Lettuce / Toadie / Covenant

Nothing remains on the persistence list today; a new subsystem (starting with v0.2.0's Jira raw
store and incremental cursors) arrives with its own paragraph here.
