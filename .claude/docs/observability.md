### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via
`getOpenTelemetry("flow")` from the `core` module. `plugins/Monitoring.kt` separately installs
Dropwizard metrics (dumped to the `metrics` logger by a `Slf4jReporter` every `monitoring.reporterIntervalSeconds`, env
`METRICS_REPORT_INTERVAL_SECONDS`: default **300**, `0` = reporter off with the meters still collected, otherwise 30..3600, anything
else fails startup via `requireConfigInt`; it was a fixed 10 s, which the memory deep dive found a main source of idle allocation and
log volume, since each dump formats the whole registry into dozens of lines the console logs exporter re-emits; nothing in the repo —
docs, e2e, tests — reads those lines, they are a look-back aid; pinned by `MonitoringReporterConfigTest`; the registry is a `TelemetryRegistry` — timers on a true sliding window of `telemetry.sampleIntervalSeconds`, capped at 300 s (the reservoir keeps ~16 bytes per timer update until it ages out of the window, so the cap bounds the memory cost per request) — published as `MetricRegistryKey`, and Ktor's JVM metric sets are OFF (`registerJvmMetricSets = false`: its thread-states gauge dumped every thread on each reporter run; the telemetry sampler reads the MXBeans itself), see "Telemetry") and the `CallId` plugin using `X-Request-Id`
(client-supplied ids are honored only when ≤64 URL-safe chars — they reach logs and OTel
attributes, so junk is ignored rather than propagated). The SDK is also wired for **logs**: a
Logback `OpenTelemetryAppender` (`server/src/main/resources/logback.xml`, the sole root appender
— re-adding STDOUT would double every line, since the interim `console` logs exporter already
prints to System.out) bridges every SLF4J log into the OTel logs SDK, and
`configureOpenTelemetry` (`plugins/OpenTelemetry.kt`) installs the appender (flushing the
pre-install boot-log buffer) while `getOpenTelemetry` (`core/.../OpenTelemetry.kt`) sets the
interim exporter to `console`. Defaults are set via `addPropertiesSupplier` (the lowest-precedence
config tier) **on purpose**, so the sink can be redirected to a collector by env alone with no
code change — set `OTEL_LOGS_EXPORTER=otlp` + `OTEL_EXPORTER_OTLP_ENDPOINT` (the
`opentelemetry-exporter-otlp` dep is already on the classpath); `OTEL_TRACES_EXPORTER` likewise.
Metrics and traces exporters default to `none` (`otel.metrics.exporter`/`otel.traces.exporter`).
**Shutdown ordering (the SDK closes LAST).** JVM shutdown hooks run concurrently and unordered. The
OpenTelemetry autoconfigure SDK registers its own hook (flush + close the logs pipeline) and Ktor's
`start(wait = true)` registers another (it fires `ApplicationStopping`, where the ingest worker releases its
leases and audits `sync_job.released`); with both live, the SDK often closed first and every audit line
emitted during Ktor's stop was silently dropped (the DB release still happened — only the log line
vanished). So `getOpenTelemetry` (`core`) calls `disableShutdownHook()` and `configureOpenTelemetry`
registers `TelemetryShutdown`'s hook instead: it waits (bounded, 20 s) for `ApplicationStopped` — the end of
Ktor's stop — and only then closes the SDK. Ktor raises an event's handlers in subscription order, so the
latch handler is subscribed from inside the `ApplicationStarted` handler (after every module has subscribed its
own `ApplicationStopped` work — the pool dispose, the Jira client close) and therefore runs last. The 20 s sits
in a budget: Ktor grace + timeout + the worker join ≈ 11 s by default, and 20 s + the SDK's ≤10 s `close()`
is exactly the Kubernetes default `terminationGracePeriodSeconds` (30 s), so `k8s/web-deployment.yaml` and `k8s/worker-deployment.yaml` set 45 s
(`JvmMemoryFlagsTest` asserts it is at least `STOP_WAIT_SECONDS` + 10 s + 15 s) — raising Ktor's shutdown timeout or
lowering the pod's grace must revisit `STOP_WAIT_SECONDS`; if the application never finished starting (a boot failure)
there is no Ktor stop to wait for and it flushes at once, so the failure's own lines still export. Anything
that must log during a stop belongs in an `ApplicationStopping`/`ApplicationStopped` handler, never in its
own JVM hook (it would race the SDK again). Pinned by `ShutdownAuditTest`: a forked child JVM (a real Netty
server + the real `configureOpenTelemetry`) gets `kill -TERM` and its stdout must hold the line its
`ApplicationStopping` handler audited (not `Process.destroy()`, which closes the child's pipes first).

**Convention for non-fatal "this should never happen" events:** emit a WARN with the
`SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow
through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary
requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out
to be a routine denial path).

**Database connection guards (WARN logs, no audit event; `persistence.md` "Connection pool").** Logger
`ch.nokillswit.infra.db.LeakedSocketGuard`: `closed the socket of a broken database connection the driver had left open (backend pid
<pid>; its transaction rolls back)` each time the guard disposes a socket r2dbc-postgresql gave up on without closing (the build-times
WHY 24 driver bug; it follows the driver's own `Connection Error` ERROR, e.g. "Response queue is full"); `leaked-socket guard disabled:
r2dbc-postgresql internals not found` once at boot (pool setup), if a driver upgrade renamed the internals it reads. A pooled session
the server ends under `idle_in_transaction_session_timeout` surfaces in Flow only as a generic connection-closed error on its next
statement (in its own request or job); the `FATAL 25P03` naming the idle timeout is in the PostgreSQL log alone.

**Audit trail.** Security-relevant events are structured INFO logs on the dedicated
`ch.nokillswit.audit` logger with the `AUDIT` marker, emitted via `audit("area.event", "key" to value, …)`
(`audit/Audit.kt`) — they ride the same Logback→OTel pipeline, so shipping them to a
collector/SIEM is env-only. Fields travel as SLF4J key/values, not in the message text (assert
with `hasKeyValue` from `TestEnvironment.kt`). Emitted today:

- `login.success` / `login.failure` (with `reason`: unknown_email/wrong_password) /
  `login.lockout` (the throttle tripping) / `login.rejected_locked` (an attempt against a locked
  account) / `login.capacity_rejected` (an unseen identity cannot be admitted into the full
  throttle); login audits use `email` for ordinary identities and a fixed-size `emailDigest` for
  oversized submissions,
- `login.mfa_challenge` (email/userId — correct credentials, second factor pending) /
  `login.mfa_success` / `login.mfa_failure` (with `reason`:
  unknown_challenge/expired/wrong_code/too_many_attempts/user_gone/credential_changed) /
  `login.mfa_unavailable` (MFA enabled on a mail-less deployment → 503) /
  `login.mfa_capacity_rejected` (the pending challenge store is full → 429, no code sent) /
  `login.mfa_send_failed` (email/errorType),
- `logout`,
- `password_reset.requested` (email) / `password_reset.unknown_email` (email) / `password_reset.throttled` (email) /
  `password_reset.capacity_rejected` (email - new identity at the full cooldown store, no worker started) /
  `password_reset.link_sent` (userId/email) / `password_reset.capped` (userId - the account already holds the maximum of 5
  unexpired links: nothing minted, nothing sent, the request still answered 202) / `password_reset.stale_account` (userId - the
  account changed between the worker's lookup and the grant insert: nothing minted, nothing sent) / `password_reset.send_failed` (email/errorType - the link never left; its grant is
  revoked) / `password_reset.rejected` (no fields - a confirm against an invalid, expired, used or stale grant, uniform) /
  `password_reset.completed` (userId - the confirm) / `password_reset.notification_failed` (userId/errorType - the post-reset
  notice; the reset stands) - the self-service reset trail, none of it carrying a token or a password; the async worker's events
  double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/revoked/malformed/
  user_gone/credential_changed/replayed — `replayed` is a rotated refresh token presented again after the replay grace,
  `revoked` a logout-revoked one or a rotation this instance no longer remembers),
- `access.rejected` (with `reason`: user_gone/revoked/credential_changed/roles_changed/malformed, `userId` - the
  token's claim, null when absent OR out of range - and `suppressed`; `plugins/Security.kt`) - the per-request access check (`TokenBlocklistService.accessVerdict`)
  refused an otherwise valid signed access token: the account was deleted, the token was logged out, the password was
  changed, the role was changed, or the token lacks a claim the check needs (a pre-deploy token). **Coalesced** like `rate_limit.exceeded` (its own `RateLimitAuditThrottle`): at most ONE event per user and reason per 60 s, `suppressed`
  counting the rejections folded into it since the previous one (0 on the first), so a stale token replayed on every request writes a line a minute;
  the keys are bounded by the accounts that ever held a signed token. Junk, expired, wrong-audience/`typ` and jti-less tokens never reach the check and emit nothing. Never the token,
- `refresh.replay_grace` (userId — a rotated refresh token presented again inside the grace
  (`security.refresh.replayGraceSeconds`) and answered with a fresh pair, at most twice per token; a lost response or a racing tab, or a replay),
- `password.changed` (targetUserId/byUserId/selfChange) / `password.change_denied` (wrong or
  missing current password),
- `user.features_changed` (byUserId/targetUserId/featuresFrom/featuresTo — only on an actual
  change),
- `user.language_changed` (the per-user language — byUserId/targetUserId + `from`/`to` codes;
  emitted only on an actual change; `user.created` additionally carries `language` when
  non-default),
- `user.created` (byUserId/newUserId/email/roles — roles as STORED, i.e. the folded
  additional-roles set) / `user.updated` (name/email deltas, only when changed) /
  `user.roles_changed` (rolesFrom/rolesTo, both as stored) / `user.deleted`
  (byUserId/targetUserId) — the Flow-user events keep their `user.` names though
  the resource and REST path are `flow-users` (renamed 2026-10-22; log searches stay valid),
- `team.created` (byUserId/teamId/name/managerUserId, nullable) / `team.updated`
  (byUserId/teamId/name) / `team.manager_changed` (byUserId/teamId/managerFrom/managerTo, nullable ids as STORED —
  only when a PUT actually changes the manager; a manager assignment grants capacity-edit rights, so it is its own
  trail line, the `user.roles_changed` precedent) / `team.deleted` (byUserId/teamId) — every team mutation; a rejected save
  emits nothing (the `team.member_added`/`team.member_removed` pair left with the Flow-login roster, V30),
- `bootstrap.admin_password_rotated` (userId/email — the fixed seed-admin email, never the password or
  its hash; `infra/db/Bootstrap.kt`, only when `ADMIN_INITIAL_PASSWORD` actually replaced the seed
  hash, so an idempotent re-boot emits nothing) / `crypto.reencrypted` (rows/label/rotating — the
  boot backfill in `infra/db/Bootstrap.kt` encrypted `rows` legacy-plaintext rows of one
  `EncryptedAtRest` service `label`, or, with `rotating` true, re-encrypted every row under the new
  key; emitted only when `rows` > 0 — so with a rotation `previousKey` configured it fires on EVERY boot of
  EVERY replica, since each rewrites the whole table until the key is dropped — and it never names a key
  or a value) — the two boot-time
  credential events, so a key rotation or admin-password reset leaves a trail beyond a log line,
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`,
  with method/path/byUserId/detail — `path` is the request path only, never the query string),
- `rate_limit.exceeded` (bucket/method/path/suppressed, plus `byUserId` on the per-USER `reports` bucket — a token bucket (`plugins/RateLimits.kt`: per-IP login, refresh,
  password-reset, password-reset-confirm, mfa, data-source-test, ready; per-user reports) rejected the request with a 429, emitted from the 429 status handler in
  `plugins/ErrorHandling.kt`; `bucket` is stamped by the bucket's own `requestKey`. **Coalesced**
  (`RateLimitAuditThrottle`): at most ONE event per bucket per 60 s, `suppressed` counting the rejections folded into it
  since the previous event (0 on the first), so an unauthenticated flood writes a line a minute per bucket instead of one
  per request; keyed by bucket only (per-IP buckets), so memory is bounded whoever floods. The per-USER `reports` bucket is keyed by
  `reports:<userId>` instead and carries `byUserId` -- the server-minted claim of a verified token, never client text; only a signed-in
  account reaches that key, so the key space is the account count. The client address is deliberately NOT a field
  (it is `X-Forwarded-For`-derived behind a proxy, i.e. attacker-chosen text; the ingress log has it), and neither is the
  query string. The per-account lockout's 429s are their own `login.lockout`/`login.rejected_locked` events and do not
  emit this),
- `data_source.created` (byUserId/dataSourceId/name/siteHost — HOST only, never the full
  `siteUrl`, and never the API token) / `.updated` (byUserId/dataSourceId/name/siteHost) /
  `.token_rotated` (byUserId/dataSourceId — its own event, separate from `.updated`, so the audit
  trail can tell a credential rotation from an ordinary settings edit without ever naming the
  token) / `.deleted` (byUserId/dataSourceId) — the v0.2.0 data-sources CRUD (V8) /
  `.scope_rejected` (byUserId/dataSourceId - null on a create/reason `OTHER_SITE`|`PROJECT_OVERLAP`/
  conflictingDataSourceId/siteHost - the REQUESTED site's host/projectKeys - the shared keys, empty for `OTHER_SITE`; emitted when
  the scope rules 409 a create or update, `ingest/DataSourceScope.kt`, never a token) /
  `.paused` (dataSourceId/jobId/reason - the trip code `LIMIT_EXCEEDED`|`TIME_EXCEEDED`/limitTrips - emitted by the worker once, when the Nth
  consecutive SYNC ceiling trip without progress pauses a connection's scheduled syncing, `ingest/SyncPauseAnnouncement.kt`; with it ONE
  ERROR line in logger `ch.nokillswit.ingest.SyncPause`, constant text plus the data source id, the code, the count and the threshold - never
  exception or host text) /
  `.resumed` (byUserId/dataSourceId/via `SYNC_NOW`|`UPDATE`/reason/limitTrips - an ADMIN's "Sync now" or config PUT lifted a pause; nothing is
  emitted when the connection was not paused) /
  `.tested` (byUserId/siteHost/ok/failedEndpoints — emitted by both `POST /api/v1/data-sources/test`
  and `.../{id}/test`; `ok` is true when every REQUIRED probe row passed, as the UI shows it, an optional row failing never clears it;
  `failedEndpoints` names every probe row that failed, optional ones included, never a token or the
  full request),
- `sync_job.requested` (byUserId/dataSourceId/jobId/kind/coalesced — `POST .../sync-jobs`;
  emitted whether or not the request coalesced into an already-open job) / `.cancel_requested`
  (byUserId/dataSourceId/jobId — `POST .../sync-jobs/{jobId}/cancel`) — the web-role surface
  (`ingest/SyncJobRoutes.kt`); `.started` (jobId/dataSourceId/kind/attempt/workerId — a job the
  worker just claimed) / `.succeeded` (jobId/dataSourceId/kind) / `.failed`
  (jobId/dataSourceId/kind/errorCode — `RUN_FAILED`, or the ceiling-trip code `LIMIT_EXCEEDED`/`TIME_EXCEEDED` (`ingestion.md` "Ceiling trips and the pause"), plus `resumable = true` ONLY on a SYNC wall-clock stop that stored pages (no failure was recorded and the log line is an INFO without a stack trace; the job row is still `FAILED`, so the telemetry panel's `sync.failures` series counts it - tell it apart by `error_code`); the exception message itself
  goes to `error_detail` on the row as `<ExceptionClass>: <message>` (U+0000 replaced by U+FFFD: PostgreSQL `text` cannot store it), not the audit line; the worker also logs a
  WARN `Sync job {id} ({kind}) for data source {id} failed` with the stack trace, each cause reduced to its class name
  by `LoggedFailure`, because a transport cause's message can embed a request URL with its query string) / `.released`
  (jobId/dataSourceId/workerId — a still-RUNNING job put back to `PENDING` on
  `ApplicationStopping`) — the worker-role surface (`ingest/IngestWorker.kt`). A run that lost
  its lease mid-way, or was cancelled via `cancel_requested_at`, logs a WARN/INFO instead of a
  further audit event — the row's own `status`/`error_code` already carries that outcome. So does a job the claim closes
  `RETRIES_EXHAUSTED` (`SyncJobLeases.claim`): a WARN `Sync job {id} ({kind}) for data source {id} exhausted its {n} attempts`,
  no `sync_job.failed`. The scheduler back-off it and every failed job record, and the config PUT that lifts it, are state
  of the already-audited mutation (`sync_job.failed`, `data_source.updated`) and add no event of their own.
- `outbound.blocked` (scheme/host ONLY — never the full URL) — a rejection by `infra/outbound/OutboundGuard.kt`'s host
  allow-list or address-range check ONLY, emitted by `GuardedDns` on the Jira HTTP client's every outbound call
  (`.claude/docs/jira-integration.md`). A DNS failure (the resolver throwing, or an empty answer, for an allow-listed host) is
  not a rejection and emits no audit event: `GuardedDns` logs a WARN per attempt, `DNS resolution failed for allow-listed outbound host
  <host>` (logger `ch.nokillswit.outbound`, host only) and the call is retried like any other IO failure.
- `metrics_settings.updated` (byUserId/configRevision) — the global `metrics.settings` singleton's
  full-replace PUT (v0.3.0 M1 commit 3, `metrics/Metrics.kt`) / `team.jira_membership_added`
  (byUserId/teamId/accountId/validFrom/validTo) / `team.jira_membership_updated` (same fields, the row's NEW values) /
  `team.jira_membership_removed` (same fields, the row's values just before deletion) — D1's dated Jira-user team
  membership CRUD (`metrics/TeamMembershipRoutes.kt`). `GET /api/v1/jira-users` is read-only, so it
  emits nothing. `team.jira_memberships_seeded` (byUserId/teamId/atlassianTeamId/requested/created/skipped — counts and
  opaque ids, never a person's or a team's name; `POST /api/v1/teams/{id}/jira-memberships/seed`, emitted only when at
  least one membership was created, the features-PUT precedent) — the batch counterpart of `.jira_membership_added`
  (`metrics/TeamMembershipSeedRoutes.kt`); `GET /api/v1/jira-teams` and `…/seed-candidates` are read-only and emit nothing.
- `public_holiday.created` (byUserId/holidayId/date/name) / `public_holiday.deleted` (same fields, the removed row's values) — the
  public-holiday dictionary's `POST`/`DELETE /api/v1/public-holidays[/{id}]` (V25, `metrics/PublicHolidayRoutes.kt`); `GET` is read-only
  and emits nothing. Both also bump the shared `config_revision` (DERIVE for every enabled connection), which is state of the audited
  mutation and adds no event of its own. The names are admin-curated labels, not personal data.
- `team.capacity_default_updated` (byUserId/teamId/defaultCapacityMdFrom/defaultCapacityMd, both nullable = none/cleared; authorizedBy) and
  `team.sprint_calendar_updated` (byUserId/teamId/sprintId/connectionId/capacityMdFrom — the previously STORED calendar re-projected onto the sprint's current window and roster (not the previous
  effective figure, which may have been a team default), null on the first save/capacityMd — the saved calendar's capacity/replacedTeamId — set when the write removed another team's leftover
  row of a re-mapped board, else null/authorizedBy) — domain-model A32's capacity edits (`metrics/SprintCalendarRoutes.kt`), ADMIN or the
  team's own manager; `authorizedBy` is `ADMIN` or `MANAGER`, the grant that let the write through. Emitted only when the
  PUT actually changed something (a canonically equal re-PUT bumps no revision and audits nothing); a denied write is the
  generic `authz.denied`. The GETs are read-only and emit nothing.
- `team.work_category_targets_updated` (byUserId/teamId/targetsFrom/targets — `key=percent` pairs in key order joined by `;`, `""` = none/cleared) —
  `PUT /api/v1/teams/{id}/work-category-targets` (V32, `metrics/TeamWorkCategoryTargetRoutes.kt`), ADMIN only. Emitted only when the stored
  set actually changed (an identical re-PUT, in any order, audits nothing); a clear is a change. The keys are admin-curated category labels, not
  personal data. A denied write is the generic `authz.denied`; the GET is read-only and emits nothing.
- `metrics_config.updated` (byUserId/dataSourceId/configRevision) — a per-connection metrics-config
  full-replace PUT that actually changed something (v0.3.0 M1 commit 4,
  `metrics/MetricsConfigRoutes.kt`); a no-op re-PUT (byte-for-byte identical to what is already
  stored) emits nothing, the features-PUT precedent. `GET .../metrics-config` and `GET
  .../metrics-config/options` are read-only, so they emit nothing.

Field-naming convention: the acting caller is `byUserId` everywhere except the auth lifecycle
events (`login.*`, `logout`, `refresh.rejected`, `access.rejected`, `password_reset.*`), where `userId` identifies the account being
authenticated.

Never log secrets (passwords, tokens); emails/ids are fine. When adding a security-relevant
mutation or denial path, emit an `audit(...)` event alongside it and extend this list in the same
change (Toadie's one sanctioned exception was a per-user view-state PUT written on every drag —
pure view state may stay unaudited, with a documented justification) — in Lettuce this catalog
grows to every user/team/content mutation, and the convention transfers wholesale. Tested in
`AuditTest` via a Logback `ListAppender` on the audit logger (the shared `LogCapture` helper in
`TestEnvironment.kt`).

**Health probes** (`plugins/Health.kt`): `GET /api/v1/health` answers `{status: ok}` whenever the
process serves requests (the image's `HEALTHCHECK`, the compose healthcheck and the k8s liveness
probe); `GET /api/v1/ready` additionally round-trips the database (one `SELECT` on `users`, 3 s
budget) and answers a `503` problem while it fails (the k8s readiness probe and the e2e global
setup wait on it); on a role that runs the ingest worker (`worker`, `all`) it also answers `503` while the scan loop's last
completed tick is older than `max(3 x ingest.schedulerTickSeconds, 60 s)`, or slot permits have been held beyond the running jobs at every
tick for that long (monotonic clock; `ingest/WorkerLiveness.kt`, published as
`WorkerLivenessKey`; `ingestion.md` "Roles"). Both are public and unaudited — they are called every few seconds and disclose
nothing. `/ready` alone rides a per-IP bucket (`ready`, 60/min, security.md; the kubelet needs 12/min), so an over-limit caller gets a `429` (the generic `rate_limit.exceeded` audit, bucket `ready`); `/health` does no I/O and is unbucketed. Tests: `HealthTest` (the `ReadinessProbeKey` seam swaps the database probe for a failing
one), `WorkerLivenessTest` (the tracker under a pinned time source), `WorkerReadinessTest` (`/ready` per role) and `IngestWorkerTest` (the permit accounting, a throwing tick still stamps).

### Telemetry

`telemetry/` (V36 `ops` schema, `persistence.md` "Telemetry (V36)") is how Flow measures itself: **every process samples itself into
Postgres, and the ADMIN read API ("Read API" below) and the panel read the rows.** Web and worker are separate JVMs with separate heaps, so
`configureTelemetry` runs in EVERY role (`application.yaml`, after `configureIngestWorker`); `telemetry.enabled` (env
`TELEMETRY_ENABLED`, default true) switches the sampler off, the suite sets it false (`TestEnvironment.configureApp`). The
`telemetry.*` block is validated on every boot, enabled or not: `sampleIntervalSeconds` 60..1800 and a divisor of 3600 (default 300; it is the stored raw `resolution`; validated by
`plugins/Monitoring.kt`, which needs it for the timers' window), `storageIntervalSeconds` 900..86400 (3600) and
`connectionEstimateIntervalSeconds` 3600..604800 (21600), the cadences of the storage and per-connection collectors below; env `TELEMETRY_*` (README).

- **Per process, every tick** (`TelemetrySampler`, a `SupervisorJob` scope on `Dispatchers.IO`; the first tick 5 s after
  `ApplicationStarted`, then aligned to the interval; cancelled on `ApplicationStopping`, before the pool is disposed): `JvmProbe` —
  heap used/committed, the old generation's peak since the previous tick (`MemoryPoolMXBean` peak, reset after each read), non-heap, direct
  buffers, process CPU as a fraction of the available processors (CPU-time delta over wall delta), `ThreadMXBean.threadCount` (never a
  thread dump), GC count and time per collector as deltas; `CgroupProbe` — `/proc/self/status` `VmRSS`, and the memory of the process's OWN cgroup (from `/proc/self/cgroup`, joined under
  the mount root, falling back to the root itself; v2 `memory.current`, `memory.stat` `anon` and `inactive_file`, `memory.peak` — a LIFETIME peak, unlike the per-interval `mem.old_gen_peak` — `memory.max`; v1 fallback; "working set" =
  current − inactive_file, the figure the kubelet evicts on); the connection pool (`PoolStatsKey`: acquired, idle, pending, allocated); and, in a web or `all` process only (`servesApi()`), `LatencyCollector` (below). A missing
  file (macOS, no memory controller, an old kernel without `memory.peak`) is a null figure and no row, never an error. The metric
  registry (names ≤ 48 chars, kind GAUGE|DELTA|PERCENTILE, unit, group) is `TelemetryMetrics.kt` — a new metric needs no migration.
- **Cluster section, one process per interval.** After its own samples a tick tries `pg_try_advisory_xact_lock(TELEMETRY_LOCK_KEY)`
  (`0x466C6F7754656C65`, "FlowTele"; `persistence.md`); the holder runs the cluster collectors in ONE transaction, each in a savepoint (a
  failing collector is undone alone and the rest still run), and commits their readings with a `telemetry.cluster_ms` marker row. A process that
  loses the try-lock, or finds the marker already at this interval's bucket, skips — so web + worker, `all`, or two replicas do the work
  once. The cluster collectors (`ClusterCollector`; per-process ones are `ProcessCollector`): **retention** (the hourly rollup, below, and the prune), then `ClusterCollectors.kt`'s
  queue, storage and per-connection estimate.
- **Requests, per web process (`LatencyCollector.kt`, `LatencyCollectorTest`).** Every tick it reads Ktor's Dropwizard TIMERS and status METERS from the registry (`MetricRegistryKey`;
  never its gauges) and writes, per normalised report route (label `/api/v1/reports/…`) and for `*` (every `/api/` route except `/api/v1/telemetry` and the `/api/v1/health` / `/api/v1/ready` probes),
  `http.count` (delta of the timer count against the value kept in memory; the first tick counts since process start), `http.p50`/`http.p95` (ms; the windowed snapshots of the
  timers that saw a request, merged into one set of observations per label, so `*` is a real percentile) and `http.5xx`/`http.429` (deltas of the per-status meters). A label has
  rows only in an interval it saw something (a 5xx/429 row only when non-zero). Series names are `ktor.calls.<route>.timer|meter|<status>` with `<route>` the whole selector chain
  (`/(authenticate "default")/(RateLimit reports)/api/v1/reports/throughput/(method:GET)`); `normaliseRoute` strips every parenthesised selector (pinned by a test against the
  names Ktor 3.6 writes). Only ROUTED series are read: a `.<status>` meter counts when its route also has a `.timer` (the timer is stopped in the same hook that marks the
  status meter; the all-status `.meter` is marked earlier, when the routed call starts, and is not used), and a name containing `?` (Ktor's fallback name for a
  response it did not route: the URI, query included) is ignored. **Ktor marks a response each time the response pipeline runs**, and `ErrorHandling`'s `status(429)`
  handler answers the RateLimit plugin's bodiless 429 a second time: on a route with wrapping selectors that second mark lands under the URI-named fallback (ignored), on
  a bare route it lands twice in the `429` meter, so the surplus of a route's status meters over its timer delta is taken off its 429 count (a thrown exception is
  counted once; a 5xx needs no correction). `previous` keeps only the names seen in the latest tick. Pinned against a real `rateLimit(...)` report route called with
  a query string. A worker has no API, so it registers no latency collector.
- **Queue (cluster, every tick).** `queue.pending` = PENDING jobs a worker could claim now (`not_before` unset or reached - a debounced DERIVE inside its quiet window is waiting by
  design) and `queue.running`, from `SyncJobsService.statusCounts()` (telemetry reads `public.sync_jobs` only through the ingest service).
- **Heavy collectors: storage and the per-connection estimate (cluster, outside the lock transaction).** The cluster transaction does only the cheap work
  (retention, the queue) and DECIDES which heavy collector is due (its marker, `telemetry.storage_ms` / `telemetry.estimate_ms`, is at least its interval older than the
  current bucket); each due one is CLAIMED by a marker row of value 0 committed with the cheap readings, so a heavy run is attempted at most once per interval whatever its
  outcome (a failure is not retried before the next interval, a rollback of the light phase claims nothing). After that commit the heavy phase runs under
  its own budget (`TelemetryConfig.heavyBudgetMillis` = 2/5 of the interval, so light + heavy fit in an interval), every statement in a SHORT transaction of its own
  (`HeavyReader`; `queryTimeout` 30 s, at most 3/4 of the heavy budget - 18 s at a 60 s interval), the readings inserted in one more transaction, and the marker set to the run's
  duration in milliseconds (at least 1; a marker still at 0 is a claim that did not complete). A failure is one WARN per collector then "recovered"; the heavy phase
  overrunning its budget is the collector `heavy`. No lock or long transaction spans the scans, so a deploy's `ALTER` is never queued behind them.
  - **Storage** (every `storageIntervalSeconds`): `db.size` (`pg_database_size`) and `db.schema.size` per `raw`/`norm`/`metrics`/`public`/`ops`: the sum of
    `pg_total_relation_size` (heap + TOAST + indexes) over relations of kind r/m. Catalog reads only.
  - **Per-connection estimate** (every `connectionEstimateIntervalSeconds`): `conn.issues` (the LIVE rows of `raw.jira_issues`: `deleted_at` and `moved_out_at` unset)
    and `conn.bytes.est`, labelled with the connection id: the sum over EVERY table of `raw`/`norm`/`metrics` that has a `connection_id` column (discovered from `pg_attribute`,
    nothing hand-listed, so a table a later migration adds is counted) of the connection's row share × `pg_total_relation_size` of the table, every stored row (tombstones
    included) weighing the same. The counts are `SELECT connection_id, count(*) … GROUP BY connection_id`, one statement per table, no payload read (`pg_column_size(t.*)`
    would de-TOAST every one); the raw tables count index-only, the big interval tables (`norm.work_item_field_intervals`, `status_intervals`, `metrics.item_stage`) as
    parallel heap scans, so their vacuum state hardly matters: ~36 ms per 6.4k-issue connection, ~0.37 s at ten (build-times WHY 40). The figure is an estimate
    (exact for one connection); soft-deleted but unpurged connections are included (they are reclaimable). `StorageEstimateTest`, `ClusterCollectorsTest`.
- **Resolution and retention.** A raw sample (`resolution` = the sample interval in seconds, so any value below 3600) is `(ts = the interval bucket, process, metric, label, n = 1, avg = max =
  value)`, written with `ON CONFLICT DO NOTHING` (a repeated tick in a bucket adds nothing). Once an hour ended at least `max(10 minutes, interval / 2)` ago, the
  next cluster section folds every complete hour after the newest hourly row into hourly rows (`resolution` 3600: `n` = sum of the samples,
  `avg` = their n-weighted mean, `max` = the largest max, so a 5-minute peak survives; for a DELTA metric `avg * n` is the hour's total) — one idempotent
  `INSERT … SELECT … GROUP BY … ON CONFLICT DO NOTHING`. Raw rows are deleted after 7 days, hourly rows after 90, and a process unseen for 90 days
  with its samples (`ON DELETE CASCADE`). The prune runs only after a rollup that INSERTED rows (about once an hour); a rollup that fails throws before it, so raw rows are never pruned while the rollup keeps failing.
- **Failure isolation (it must never be the reason something else is slow or down).** Every collector runs in `catchingFailures` and is
  independent of the others; the whole tick runs under `withTimeoutOrNull(interval / 2)`; every statement under the transaction's
  `queryTimeout` (a quarter of the interval, 15..60 s; the only way a statement timeout may be set). Per collector, the FIRST failure is one WARN
  (`Telemetry collector <name> failed: <exception toString>`, no stack), repeats are DEBUG, and the first success afterwards one INFO
  `Telemetry collector <name> recovered` (logger `ch.nokillswit.telemetry.TelemetrySampler`; the tick budget is the collector `tick`). It touches neither
  `/ready` nor the ingest worker's liveness, and no audit event: it records no user action. Pinned by `TelemetrySamplerTest`.
- **Never recorded:** JVM arguments, environment variables, system properties, request paths with identifiers, any project data. A process
  row holds the role, the instance (`HOSTNAME`, else the resolver's host name, ≤ 100 chars), the JVM start, the build version and commit
  (the image's `FLOW_BUILD_VERSION`/`FLOW_BUILD_COMMIT`, stamped with `--build-arg`; null outside the image), the available processors,
  the heap maximum and the container memory limit. The `ops` tables hold only numbers and these labels.
- **Seams** (lower layers publish, `telemetry/` reads; nothing imports it — `PackageBoundaryTest`): `plugins/Monitoring.kt`'s `MetricRegistryKey`
  and `infra/db/Database.kt`'s `PoolStatsKey` (pinned present for r2dbc-pool 1.0.2 by `PoolStatsTest`); the test seams are
  `TelemetryOverridesKey` (clock, probes, identity, first-tick delay) and `TelemetrySamplerKey` (the running sampler).
- **Read API (ADMIN; `TelemetryRoutes.kt`, `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`, `TelemetryWire.kt`; `authorization.md`).** Two GETs, registered with the feature
  routes (web/`all` only), every statement under a 5 s `queryTimeout`. `GET /api/v1/telemetry/current` answers `{generatedAt, sampleIntervalSeconds, processes[], storage, connections[], requests}`:
  `processes` = every process seen in the last 24 h that is live, every stale one seen within the last hour (a wedged replica, a just-replaced pod), plus per role the newest older stale one when none is alive (`stale` = no tick, or no sample, for more than 3 sample intervals: `last_seen` or the newest sample's bucket that old, or no sample at all for a process older than that), each with the figures of ITS newest raw
  bucket (a figure that bucket did not write is null, never carried over): the `memory` block (heap used/committed/max, old-gen peak, non-heap, direct, rss, cgroup current/anon/working set/peak,
  `containerLimitBytes`, `percentOfLimit` = working set / limit in percent, one decimal, null without a limit) and the `jvm` block (cpu ratio, threads, GC count/time summed over collectors, the four pool figures);
  `storage` = the newest `db.size` / `db.schema.size` measurement; `connections` = every not-yet-purged data source (id, name, enabled, `deleted`, the newest estimate's `issues`/`estimatedBytes`/`estimatedAt`, `freshness`,
  the live `queue`, `lastSync`, `lastBackfill`, `rateLimited24h`/`backoffMs24h` from the `jira*` progress keys of SYNC jobs that ended in the last 24 h, `derive` = the latest ended run + the longest of the last 7 days);
  `requests` = the newest bucket of the live web/`all` processes (counts and errors add up, p50 weighted by the requests of the processes that wrote one, p95 the largest): the `*` total and the 15 busiest routes; `windowSeconds` is the counts' window (the sample interval), `percentileWindowSeconds` the p50/p95 window (the interval capped at 300 s, the timers' reservoir). `GET /api/v1/telemetry/history?group=memory|storage|sync|derive|requests|jvm&range=24h|7d|90d`
  (both required, a `400` otherwise) answers `{group, range, bucketSeconds, from, to, historyFrom, truncated, series[{key, metric, label, role, connectionId, unit, kind, points[{t, avg, max}]}]}`.
  Buckets: 24h = the sample interval (widened to a whole multiple of it only if 24 h would exceed 400 points, i.e. an interval under 216 s), 7d = 3600 s (hourly rows PLUS the raw rows after the newest hourly row, so the
  current hours are never missing and nothing is counted twice), 90d = 21600 s (hourly rows grouped to six hours in SQL). Per-process groups (memory, jvm, requests) merge the processes of one `role` into one series
  (`key` = `<role>:<metric>[:<label>]`) so a trend survives a restart; `requests` series merge ALL serving roles per route (`key` = `<metric>:<route>`, `role` null: web and `all` are one fleet); storage and the queue are cluster-wide (`role` null). A GAUGE/PERCENTILE point is the n-weighted mean and the largest sample; a DELTA point's `avg` is the bucket's
  TOTAL and `max` the largest single interval (a read-time count has no finer grain: its `max` EQUALS its `avg`, the bucket total). `sync`/`derive` also carry four read-time series per connection from the job tables (`sync.runs|failures|duration_ms|rate_limited`, `derive.runs|failures|duration_ms|rows_written`,
  bucketed by `finished_at`; a failures / rate-limited series that never left zero is omitted, absent = 0; a SYNC wall-clock stop that stored pages (`TIME_EXCEEDED`, resumable) is a FAILED job row and so counts as a failure here). `requests` keeps `*` (all five metrics) and the ten busiest routes of the range (count, p50, p95 only: the series cap).
  At most 400 points per series and 40 series (`truncated` when more matched, in registry then label order); `sync`, `derive` and `storage` have their own budget of 25 connections (x 4 series for sync/derive, + sync's 2 queue series; x 2 series for storage, + its always-kept database and per-schema series), never cutting a connection: past 25, the connections with the fewest ended runs in the range (storage: the smallest latest `conn.bytes.est`, ties the higher id) are left out whole, `truncated` is set and their ids are in `omittedConnectionIds`. `historyFrom` = the oldest instant the range can hold, clipped to the range: the first process start for the sampled groups, the oldest ended job / DERIVE run still retained (`SyncJobsService.oldestFinishedAt`, `MetricsStore.oldestFinishedAt`) for
  `sync`/`derive`. Both reads ride the per-user `telemetry` bucket (30/min, security.md). Pinned by `TelemetryRoutesTest` (clock pinned through `TelemetryOverridesKey`).
- **Registry bound:** the `TelemetryRegistry` never registers a `ktor.calls.` name containing `?` and registers at most 2,000 `ktor.calls.` names (security.md "Telemetry"); the Slf4jReporter filters `?` names.
- **Tests:** `CgroupProbeTest`, `JvmProbeTest`, `TelemetryConfigTest`, `TelemetryStoreTest` (insert, rollup, prune boundaries, cascade, the lock),
  `LatencyCollectorTest`, `StorageEstimateTest`, `ClusterCollectorsTest`, `TelemetrySamplerTest`, `TelemetryRoleTest` (web and worker both sample), `TelemetryRoutesTest` (the read API), `TelemetryReadBudgetTest` (owner reads run under the read transaction's statement timeout), `PoolStatsTest`, `TelemetryRegistryTest` (the sliding window, no JVM gauge sets).

### Not yet ported

The data-sources CRUD and Test-connection audit trail, `outbound.blocked`, and the sync-job queue's
lifecycle events (`sync_job.requested`/`.cancel_requested`/`.started`/`.succeeded`/`.failed`/
`.released`) have all landed. REFERENCE, ISSUES, CHANGELOGS, WORKLOGS and RECONCILE
(`.claude/docs/ingestion.md` "Streams") all run for real now, but none of them emits a stream-level
audit event of its own — only `sync_job.*` brackets the whole job. **Per-stream progress instead
lives entirely in `sync_jobs.progress`/`current_stream`** (`StreamContext.incrementProgress`/
`currentStreamName`, flushed on every `heartbeat()`, surfaced read-only at
`GET …/{id}/status` — see `.claude/docs/ingestion.md` "Progress counters"/"Sync status endpoint"),
**not the audit trail**: it is a best-effort operational signal (a page count, which stream is
active), not a security-relevant event, so it deliberately does not go through `audit(...)`. PROCESS
and PROFILE follow the same rule (`issuesProcessed`/`issuesFailed`, `profileComputed`), and so does the ISSUES stream's skip of a bad
issue (`issuesSkipped` plus a WARN naming the issue key and id, never the payload — `ingestion.md` "ISSUES: a bad issue is skipped, loudly"). Any FUTURE stream-level
audit trail (as opposed to progress) arrives with a later commit and gets its own paragraph here.
