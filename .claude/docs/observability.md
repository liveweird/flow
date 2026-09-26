### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via
`getOpenTelemetry("flow")` from the `core` module. `plugins/Monitoring.kt` separately installs
Dropwizard metrics (logged via SLF4J every 10s) and the `CallId` plugin using `X-Request-Id`
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
**Convention for non-fatal "this should never happen" events:** emit a WARN with the
`SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow
through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary
requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out
to be a routine denial path).

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
- `password_reset.requested` / `password_reset.unknown_email` / `password_reset.throttled` /
  `password_reset.capacity_rejected` (new identity at the full cooldown store, no worker started)
  / `password_reset.completed` (email/userId) / `password_reset.send_failed` (email/errorType —
  the email never left) / `password_reset.store_failed` (email/errorType — the email WAS delivered but
  the new hash was not stored, so the recipient holds a password that does not work) — the
  self-service reset trail; the async worker's events double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/revoked/malformed/
  user_gone/credential_changed),
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
  (byUserId/targetUserId),
- `team.created` (byUserId/teamId/name/members — the initial roster size) / `team.updated`
  (byUserId/teamId/name) / `team.deleted` (byUserId/teamId) / `team.member_added` /
  `team.member_removed` (byUserId/teamId/targetUserId) — every team mutation; a rejected save
  emits nothing,
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`,
  with method/path/byUserId/detail),
- `data_source.created` (byUserId/dataSourceId/name/siteHost — HOST only, never the full
  `siteUrl`, and never the API token) / `.updated` (byUserId/dataSourceId/name/siteHost) /
  `.token_rotated` (byUserId/dataSourceId — its own event, separate from `.updated`, so the audit
  trail can tell a credential rotation from an ordinary settings edit without ever naming the
  token) / `.deleted` (byUserId/dataSourceId) — the v0.2.0 data-sources CRUD (V8) /
  `.tested` (byUserId/siteHost/ok/failedEndpoints — emitted by both `POST /api/v1/data-sources/test`
  and `.../{id}/test`; `failedEndpoints` names the probe rows that failed, never a token or the
  full request) — worker-side sync-job events (`sync_job.*`) arrive with the sync-job queue (plan
  commit 5).
- `outbound.blocked` (scheme/host ONLY — never the full URL) — every rejection from
  `infra/outbound/OutboundGuard.kt`'s host allow-list or address-range check, emitted by
  `GuardedDns` on the Jira HTTP client's every outbound call (`.claude/docs/jira-integration.md`).

Field-naming convention: the acting caller is `byUserId` everywhere except the auth lifecycle
events (`login.*`, `logout`, `refresh.rejected`), where `userId` identifies the account being
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
setup wait on it). Both are public and unaudited — they are called every few seconds and disclose
nothing. Tests: `HealthTest` (the `ReadinessProbeKey` seam swaps the database probe for a failing
one).

### Not yet ported

The data-sources CRUD and Test-connection audit trail, plus `outbound.blocked`, have landed;
sync-job lifecycle events (`sync_job.started`/`.succeeded`/`.failed`/`.released`) arrive with the
sync-job queue and worker (plan commit 5) and get their own paragraph here.
