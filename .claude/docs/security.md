### Security posture: development vs production mode

`ktor.development` is env-overridable (`$KTOR_DEVELOPMENT:true`): local `:server:run`/tests
default to development mode; **the Docker image ships `KTOR_DEVELOPMENT=false`** (production
mode), and `docker-compose.yaml` explicitly sets it back to `true` because it is the local
plain-HTTP demo. Production mode activates HSTS + HTTPS redirect and **fail-closed startup
checks**:

- **JWT secret** (`plugins/Security.kt`): production requires a private 64-character hex key
  generated with `openssl rand -hex 32`. Blank, malformed, repeated-character and publicly
  committed values (including the compose demo key and Kubernetes template placeholder) refuse
  startup. Development warns and retains its demo behavior. The format check cannot establish
  randomness; generate and store the key privately.
- **Seed passwords** (`infra/db/Bootstrap.kt`, module `configureBootstrap`, runs after
  `configureDatabase`): in production mode, if any active account still carries the well-known
  `changeme` bcrypt hash the app **refuses to start**. The seed admin is also checked against
  known plaintexts so a freshly salted hash of an unsafe initial password from an older build
  cannot pass that hash-equality check. Setting `ADMIN_INITIAL_PASSWORD` (config
  `bootstrap.adminInitialPassword`) rotates the V3 seed admin's password at startup — idempotent:
  only applied while the admin still has the seed hash, so an admin-chosen password is never
  overwritten. Before rotating in production, bootstrap rejects the known `changeme`/`CHANGE-ME`
  values and applies the normal account password length and bcrypt byte limits. Covered by
  `BootstrapTest`; seed-mutating tests restore state via `TestSeedState.restoreSeedAccounts()`
  (`TestEnvironment.kt`).
- **GitLab outbound** (`gitlab/GitLabConfig.kt`, `infra/outbound/SelfManagedHosts.kt`, `CaBundle.kt`): `gitlab.stubBaseUrl` must be blank in
  production; in **any** mode a malformed `gitlab.allowedHosts` entry (wildcard, IP literal, scheme/path/credentials), a malformed or
  too-wide `gitlab.allowedCidrs` entry (`/0`, IPv4 wider than /8, IPv6 wider than /32, anything overlapping loopback, link-local, any-local,
  multicast, 6to4, Teredo, mapped or NAT64), CIDRs or a `gitlab.caBundle` without hosts, an unreadable or certificate-less CA bundle, an
  out-of-range `gitlab.requestTimeoutSeconds`/`gitlab.maxRetries`/`gitlab.requestDeadlineSeconds`/`gitlab.usersRefreshHours`/`gitlab.commitFilesPerRun`/`gitlab.maxResponseBytes`, or an explicit `gitlab.maxResponseBytes` above
  `jira.maxResponseBytes` refuse startup. See "Outbound HTTP calls" below; covered by `GitLabBootTest`.
- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including
  password-reset links and MFA codes) into the application log — permitted with a warning in development,
  **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode.
  A non-blank `mail.appUrl` that is not the deployment's `https` origin (`http` only in development; no
  path, query, fragment or userinfo) refuses to start in **any** mode (blank is allowed: the reset answers 503
  and boot warns). See "Outbound email" below.
- **Data-encryption key** (`infra/crypto/Crypto.kt`, module `configureCrypto`, right after
  `configureMail` and BEFORE `configureDatabase`): blank or the burned repo-committed dev default
  → warn in development, **refuse to start** in production; a malformed key (not 64 hex chars —
  the k8s template placeholder included) refuses in ANY mode. See "Encryption at rest" below.
  Covered by `CryptoBootTest`; every production-mode boot test overrides
  `security.encryption.key` with `strongEncryptionKey()`.
- **Data-source scope rules** (`ingest/DataSourceScope.kt`, `configuredScopeRules()` first thing in `configureDatabase`,
  after `configureCrypto`): `ingest.scopeRules` (`INGEST_SCOPE_RULES`, default `enforced`) set to `off` **refuses to
  start** outside development mode - the one-Jira-site / no-project-in-two-sources rules can never be off in
  production (the switch exists for the isolated e2e stack only, which runs in development mode); a value other than
  `enforced`/`off` refuses in ANY mode. Covered by `DataSourceScopeBootTest` (a production-mode boot overrides the
  JWT/mail/crypto settings first, like the other boot tests).

**JWT model** (`auth/Tokens.kt` + `plugins/Security.kt`): login issues an access/refresh **pair**,
both carrying `email`, `userId`, a `roles` array, a `typ` claim (`"access"`/`"refresh"`), a
`credentialRevision` and a random `jti`. The `jwt {}` verifier requires audience (`flow-api`), issuer
(`http://0.0.0.0:8084/` by default), `typ == "access"`, and a PRESENT `jti` (a jti-less token could never be
revoked, so both the verifier and `/refresh` reject it outright). **Every authenticated request then runs ONE
uncached database statement** (`TokenBlocklistService.accessVerdict`, ported from Lettuce's `isSessionLive` and
extended; `revoked_tokens` is written by `/logout` and by the refresh rotation, the revoke path prunes expired
rows): it reads the token's user row (`role`, `credential_revision`, `marked_as_deleted`) and whether the `jti` is
blocklisted, and the token is accepted only when the account exists and is not deleted, the `jti` is not
blocklisted, the token's `credentialRevision` equals the stored one and its `roles` equal the stored role. Anything else is a `401`
audited `access.rejected` with a `reason` (`user_gone`, `revoked`, `credential_changed`, `roles_changed`, and
`malformed` for a signed access token lacking `roles`, or whose `userId` (1..4294967295) or `credentialRevision` (non-negative) is not an integral JSON
number - the same `strictLongClaim` rule as `/refresh`, so `1.0`, `1e0` and a numeric string are rejected on both paths -
a pre-deploy token, which the SPA silently refreshes), all with ONE response text ("Access token is no longer
valid - refresh or sign in again"; the audit event is coalesced to one per user and reason per minute with a `suppressed` count, like
`rate_limit.exceeded`, so a client replaying a stale token writes a line a minute, not a line per request). Structural rejections (junk, expired, wrong audience/`typ`, no `jti`) stay
unaudited and never reach the database. Nothing is cached: a logout, an admin delete, a demotion/promotion or a
password change/reset ends the token on its very next request, on any replica (the old 30-second verdict cache,
`REVOCATION_CACHE_TTL_MS`, is gone); the price is one primary-key read per request (about 1 ms). A role change does NOT bump the
credential revision, so the SPA's `401` -> `/refresh` succeeds with the new roles. Not compared: the MFA flag and
other feature flags (they ride the token until the next refresh), lockout, name/email/language. Pinned by
`StaleAccessTokenTest`. **A lookup that THROWS inside the JWT `validate` block
(database unreachable, pool acquire timeout) is an outage, not a bad token:** ported from
Lettuce, `validate` runs the lookup through `catchingFailures` (`infra/Failures.kt`)
and, on failure, stashes the cause on the call (`BlocklistFailureKey` in `plugins/Security.kt`);
Ktor's JWT provider turns any throw out of `validate` into a plain challenge, so the challenge
answers the catch-all's logged `500` (`respondInternalError`, `plugins/ErrorHandling.kt`) instead
of `401` — the SPA reads a `401` as session expiry and would sign the user out over a transient
outage. A malformed/expired/wrong-audience/wrong-`typ` token never reaches the lookup and stays
the uniform `401`. Pinned by `BlocklistOutageTest`; the OpenAPI spec already declares `500` on
every operation and the coverage gate ignores it (`CROSS_CUTTING_STATUSES` in
`OpenApiConformance.kt`), so no spec change is needed. Full token/refresh/logout semantics: see
"Authorization model" in `.claude/docs/authorization.md`.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in
`localStorage` under `flow.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId,
disabledFeatures and a random session identity; no password is ever persisted; everything else in
localStorage is view state). A deliberate trade-off, documented rather than hidden: any script
running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP — so the
real control is keeping foreign script out. **The refresh token's lifecycle (rotation with a short replay grace):**
`POST /api/v1/refresh` verifies the presented refresh token (signature, issuer, audience, a PRESENT `exp`, `typ`, a
present `jti`, not blocklisted, the user still active, `credentialRevision` current) and then CLAIMS it: an atomic
`INSERT .. ON CONFLICT DO NOTHING` of its `jti` into `revoked_tokens` (until the token's own expiry;
`TokenBlocklistService.revokeIfAbsent`, pinned directly by `TokenBlocklistRotationTest`), after which a fresh
access/refresh pair is minted. Only the claim is atomic; delivery of the response is not, so of any number of
concurrent calls with one token exactly one inserts the row (`CLAIMED`) and the rest are decided by the grace below.
**Replay grace** (`security.refresh.replayGraceSeconds`, `$REFRESH_REPLAY_GRACE_SECONDS`, default 60, 0 = strict
one-time use): a `/refresh` response can be lost after the claim committed (the SPA's 30 s deadline, a dropped VPN, a
reload mid-flight), leaving the client holding the already-retired token. So a replay of a token THIS instance rotated
less than the grace ago is answered with another fresh pair (audit `refresh.replay_grace`, `userId`) instead of a `401`
that would sign the user out; the same grace answers the loser of two tabs refreshing at once (the rotation is
recorded inside the claim's transaction, before its commit, so a racing loser, which only resumes after that commit,
always finds it). One token gets at most 2 such re-mints (`MAX_GRACE_REUSES`; a lost response or a racing tab needs one);
after that, or after the window, a replay is rejected. **A logout that REACHES the route body ends the grace**: it
drops every rotation record of the access principal's user and of the presented refresh token's own user
(`TokenBlocklistService.forgetRotations`), so a copy of a recently rotated token cannot mint after sign-out and its replay
is `reason=revoked` (it also ends the grace of that user's other devices, whose next lost-response retry then signs out:
strict, never lax). `/logout` sits behind `authenticate {}`, so it needs a valid ACCESS token: the SPA's `logout()`
(`web/src/api/auth.ts`) therefore, on a `401` (the stored access token expired after idle or a lost refresh response),
exchanges the captured refresh token for a new pair without touching the already-cleared local session
(`refreshPairDetached`) and calls `/logout` again with the NEW pair, so the live refresh token is the one revoked; if that
refresh fails the sign-out stays local only and the refresh token lives until its own expiry (<= 1 h). A direct API caller
that logs out with an expired access token gets the plain `401` and revokes nothing. Known limitation: a refresh and a
logout racing within the same few milliseconds can leave the freshly minted pair unrevoked, bounded by the refresh TTL. The rotation records (`auth/RefreshRotations.kt`, `RefreshRotationsTest`) are in memory
and per instance like the lockout/MFA state (single web replica) and capacity-bounded at 10,000: when full, records whose
token has expired or whose grace has passed are evicted first and, if it is still full, the NEW record is dropped. A
restart, or a dropped record, loses them and the behaviour falls back to strict. Outside the grace, a replay of a rotated token the instance still remembers is a `401`
audited as `refresh.rejected reason=replayed`; a token blocklisted by logout (or whose rotation is not remembered) is
`reason=revoked`, so the trail separates theft/replay from logout. The accepted cost of the grace: a stolen token replayed
within the grace of its legitimate rotation also gets a pair, at most twice (the window is short, the replay is audited and a logout ends it). There is
deliberately NO family-wide revocation and no "sign out everywhere": a replay outside the grace does not end the live
chain, and a stolen token that refreshes FIRST makes the victim's next refresh the rejected replay (the victim is signed
out and the trail shows `replayed`). The access token a rotation supersedes is not revoked: it stays valid until its
own expiry (<= 15 min; only a logout blocklists an access token, and the per-request check above ends it on any
delete, role change or password change). `POST /api/v1/logout` revokes the access token it is
called with and the one refresh token the client sends in the body (best-effort; both jtis go to the blocklist); it does
NOT end other pairs of the same user. What does end every outstanding refresh token of a user is a password
change/reset/bootstrap rotation (the `credential_revision` bump) or an admin deleting the account (`/refresh` re-reads
the user), and the same events end the ACCESS token at its next request (the per-request check above); an idle session
also ends at the refresh TTL. The cost is one `revoked_tokens` row per refresh (about one per
15 minutes per active session, pruned at the next revoke once expired). The SPA copes with two tabs sharing one session
(`web/src/api/http.ts`, `web-internals.md`). The keep-script-out control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest`: a strict CSP (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
`object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` — the whole string is pinned
verbatim in `ServerTest`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()`
(also pinned verbatim), and — production mode only, `plugins/Http.kt` — `Strict-Transport-Security`
(`max-age=31536000; includeSubDomains`, pinned by `ServerTest`'s production-mode boot behind
`http.behindProxy` with `X-Forwarded-Proto: https`; absent in development). Every `/api/` JSON and problem+json answer additionally carries
`Cache-Control: no-store` (`plugins/Http.kt`'s `CachingHeaders`, pinned by `ServerTest` together
with the unchanged caching of the SPA's static assets), so per-user data never lands in a browser
or proxy cache -- including the `200`/`304` of the report GETs, which add an `ETag` to the same `no-store` ("Caching" below). `text/html` (index.html and every SPA deep link answered with it) carries `Cache-Control: no-cache`
(revalidate every time, so a deploy's new hashed asset names are never masked by a stale shell), while the content-hashed
CSS/JS keep `max-age=86400`. The `Server` header is the fixed generic `flow` (`install(DefaultHeaders) { header(Server, …) }`
in `Http.kt`) — Ktor's default `Server: Ktor/<version>` disclosed the framework and its exact version; pinned in `ServerTest`
(no `Ktor` in it).

**Caching -- a settled decision (2026-10-28: the owner chose `no-store`).** The report GETs (`/api/v1/reports/**`, `reports/ReportValidators.kt`,
`.claude/docs/reports.md` "Cache validators") answer a `200` or `304` with a weak `ETag` AND `Cache-Control: no-store` (no `Vary: Authorization`), so nothing lands
in a browser or proxy cache. The server-side `304` therefore helps only a client that sends `If-None-Match` itself, which the SPA does not (it
re-fetches every time); it saves the report computation, not the transfer from a browser cache. The rejected alternative was `private, no-cache` +
`Vary: Authorization` + a logout `Clear-Site-Data: "cache"`, which would let browsers revalidate cheaply but would leave report bodies (person
names, estimates, worklog figures) on disk between logins on the same profile (best-effort clearing only: an idle-out or revoked session never calls
logout, Safari ignores the directive). Cross-user safety of the `304` rests on the same grounds either way: report content depends only on the query
and the derived data, never on the caller (D12) -- the one caller-shaped answer, Compare people's capacity rows, carries the caller's verdict inside
its `ETag`, so a caller who loses the grant can never be told by a `304` to keep a body with the rows (nor a newly granted one to keep a body without them) -- and authentication runs BEFORE any
validator logic, so an unauthenticated request is `401` with no validator and a `304` is never a way to probe for data. Only the `application/json`
`200`/`304` of the report GETs carry a validator (`plugins/ResponseValidators.kt` writes it, after the body is rendered, only onto those; a
`problem+json` response -- including a report's `400`/`500` -- is always `no-store` and carries no validator); every other endpoint keeps the blanket
`no-store` without one.

`style-src` carries `'unsafe-inline'` deliberately: Mantine's style props/CSS variables render
inline `style` attributes and runtime `<style>` tags, and the app uses `style={{}}` itself — a
STYLES-only concession; it does not reopen script injection.

The Swagger UI is the ONLY CSP exemption (it bootstraps with inline script/style), and it applies
only when `http.exposeOpenApi` is on (`Application.exposesOpenApi()`, the same definition that
mounts the UI: the property when set, else development mode) AND the path is exactly `/openapi` or
starts with `/openapi/`. With it off — production by default — `/openapi`, `/openapi/x` and
look-alikes such as `/openapiX` are ordinary paths (the SPA catch-all answers them with
`index.html`) and carry the full CSP. The exempt paths still get the non-CSP hardening headers.
All of it is pinned in `ServerTest`.

Consequences: never weaken `script-src` (no CDN scripts, no `unsafe-inline` for scripts — Vite
bundles everything same-origin, fonts included), and the SPA must stay free of
`dangerouslySetInnerHTML`/`eval` sinks (currently zero). Moving tokens to httpOnly cookies would
trade this for CSRF machinery — revisit only with that full picture.

**Login timing equalizer** (`auth/AuthRoutes.kt` + `TIMING_EQUALIZER_HASH` in
`auth/Passwords.kt`): an unknown email pays a full, discarded bcrypt verify against a fixed
cost-12 hash so its 401 takes as long as a wrong password's — without it, response timing is an
account-enumeration oracle (the reset path equalizes via async processing; login equalizes
in-line).

**Per-account login lockout** (`auth/LoginThrottle.kt`, wired in `configureAuthRoutes`): after
`security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one
submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900,
`$LOGIN_LOCKOUT_DURATION_SECONDS`) — even with the correct password, and regardless of whether the
account exists (no enumeration signal). A success resets the counter. The in-memory, per-instance (so the web tier runs ONE replica — `ci.yml` `k8s-static` pins it)
store has a hard `security.lockout.maxTracked` limit (default 10,000,
`$LOGIN_LOCKOUT_MAX_TRACKED`): expired state is reclaimed through an expiry index, but fresh
counters and active locks are never evicted to admit a new identity. At capacity, an untracked
identity receives an audited `429` before password verification, so saturation cannot bypass the
account throttle; tracked identities can still finish their normal flow. A fixed-size digest of
the canonical submitted identity bounds retained key size even for malformed login input;
oversized identities are also digested in audit fields instead of logged in full. The deployment
is single-replica; a restart resets the throttle. This complements the per-IP `RateLimit` bucket,
which rotating hosts sidestep. The 429 is **thrown** (`TooManyRequestsException`), never
`respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages
429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit,
injected clock) + `LoginLockoutTest` (route). The SPA shows a neutral temporary sign-in limit for
either 429 cause.

**Person trend's peer-cell cache** (`reports/PersonTrendCache.kt`, `.claude/docs/reports.md` "Report 20", Cost) is in-memory state of the same kind: per instance and per `Application` (a second web replica would only lower the hit rate; each re-validates its generation against the database on every request), capacity-bounded by `reports.peerCellCache.maxEntries` (default 20,000 fixed-size entries; a full generation refuses writes, it never evicts) and holding only report figures every signed-in user may read (D12, nothing caller-shaped; Compare people, whose capacity rows are, never uses it).

**Self-service password reset by confirm link** (`auth/PasswordResetRoutes.kt`, `auth/PasswordResetService.kt`, V35; SPA:
"Forgot password?" on `/login` → `/reset-password`, the emailed link opens `/reset-password/confirm`; Toadie's design, 2D3).
The public `POST /api/v1/password-reset` takes `{email}` and answers a uniform `202` for any well-formed request - the
lookup, grant issuance and email delivery run **asynchronously** after the response (uniform latency, no existence oracle),
and the request **changes nothing**: no password, no session. An unknown or soft-deleted account (`findWithIdByEmail`
filters active rows) sends nothing. Each link carries a 256-bit `SecureRandom` token (43-char base64url); only its SHA-256
digest is persisted in `password_reset_tokens` with the user id, the **account snapshot** (`credential_revision` AND
`email`) and the expiry. Lookup is SQL equality on the digest, so no constant-time compare is needed (OWASP Forgot-Password
cheat sheet). The lifetime is `security.passwordReset.tokenTtlSeconds` (`$PASSWORD_RESET_TOKEN_TTL_SECONDS`, default 900,
boot-validated 1..3600). **Several links may coexist** - a newer request never revokes a usable one, or anyone typing an
address into the form could kill the owner's link; a failed delivery revokes only the grant it just minted
(`password_reset.send_failed`); expired rows are pruned on every issue. **An account holds at most 5 unexpired grants** (a constant in
`PasswordResetService`, counted under the user-row lock so concurrent requests cannot slip past it): at the cap `issue` mints
nothing, the worker sends no email and audits `password_reset.capped` (userId only), and the request route still answers `202`
(no oracle). Earlier links stay valid. This bounds the remaining nuisance of the unauthenticated form - anyone who knows an
address can still make the owner receive reset mail, but at most 5 live links per lifetime (15 min by default) per account,
on top of the one-request-per-minute throttle. A worker that finds the account moved between its lookup and the grant insert
audits `password_reset.stale_account` (userId) and sends nothing. A grant dies on **use** (confirming deletes every grant
of that user), a **password change** (the captured `credential_revision` no longer matches), an **email change** (the captured
`email` no longer matches - a Flow deviation from Toadie, whose `auth_version` moves on email edits; Flow's revision moves
only on password changes and must stay that way), an account **soft delete** and **expiry**; a role change does not
invalidate it. The email renders through `LocalizedText` in the RECIPIENT'S stored language (V1 `users.language`, EN fallback);
no email carries a password.

The link is `<mail.appUrl>/reset-password/confirm#token=…`. The token sits in the URL **fragment**, which never reaches the
server, its access logs or `Referer`; the SPA strips it from history and keeps it in component memory, and loading the page
consumes nothing (there is deliberately no token pre-check endpoint - an invalid token is learned at submit). The origin is
`mail.appUrl` (`$MAIL_APP_URL`), **configured, never derived from the request `Host`**, and validated at boot by
`configureMail` in any mode: a non-blank value must be an `https` origin (`http` only in development), with a host and no
path beyond `/`, userinfo, query or fragment, at most 2048 characters - otherwise startup is refused (a wrong origin would mail
reset tokens to another host). A **blank** value is allowed: the request route then answers `503` ("set MAIL_APP_URL") and boot
logs ONE `warn` when the transport is not `disabled`. `503` also when the deployment has no outbound email
(`MAIL_TRANSPORT=disabled`).

Public `POST /api/v1/password-reset/confirm` takes `{token, password}`. The grant is checked FIRST (`isUsable`, before any
bcrypt: a junk token never costs a hash; for the PASSWORD rules the 401 wins over a 400 - a body that fails to decode is a
400 first), then `validatePassword` (a refused password is a `400`
that does **not** consume the grant), then `PasswordResetService.complete` does consumption, the password write
(`updatePasswordInTransaction`, conditional on the captured revision) and the deletion of every sibling grant in **one
transaction**, under a `FOR NO KEY UPDATE` lock of the user row (persistence.md "Row locks vs. FK locks"), so competing confirmations (across instances too) have exactly one
winner. An unknown, malformed, expired, used or stale grant is one uniform `401` (`password_reset.rejected`). Success is `204`
with **no auto-login**: the `credential_revision` bump signs every other session out through the per-request access check and
kills pending refresh tokens and MFA challenges, while MFA stays as it was, so the next login still asks for the code. A
best-effort localized notice ("your password was reset, sessions signed out"; no token, no password) is emailed after the
response; its failure (`password_reset.notification_failed`) cannot undo the reset. A confirmed reset does **not** clear the
per-account lockout (in-memory, at most 15 minutes, per instance). A lost 204 can make a retry answer `401`: sign in with the
chosen password or request another link.

Throttling: per submitted email - one request per `security.passwordReset.minIntervalSeconds` (default 60,
`$PASSWORD_RESET_MIN_INTERVAL_SECONDS`) - uniformly for existing and unknown addresses (`auth/PasswordResetThrottle.kt`,
in-memory/per-instance like the lockout), with the hard `security.passwordReset.maxTracked` limit (default 10,000,
`$PASSWORD_RESET_MAX_TRACKED`: elapsed cooldowns are reclaimed, a fresh one never evicted; saturation rejects a new identity with
an audited `429` before any worker starts); the per-IP `password-reset` bucket (`security.rateLimit.passwordResetPerMinute`,
`$PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` - blank follows the mode: 5/min production, 100/min development); and the confirm
step's own per-IP `password-reset-confirm` bucket, 10/min in every mode (not configurable - the MFA idiom). `Cache-Control:
no-store` already rides every `/api/` JSON and problem+json answer (`plugins/Http.kt`, StatusPages' 429s included); the bodiless
`204` has nothing to cache. The development `log` mail transport prints the link: it must stay inaccessible outside a local
demo (production refuses it). Tests: `PasswordResetServiceTest` (injected-clock expiry, digest storage, the single-use race,
revision/email/delete staleness, the 5-grant cap, the revision-conditional write that lets only one of two competing writers win, and
a completed reset followed by an admin password write: both succeed, the admin's password is the final one), `PasswordResetConfirmationTest` (the confirm route, its own
bucket, the byte ceiling, MFA, notification failure, the `mail.appUrl` boot contract), `PasswordResetTest` (the request route and
the full link roundtrip incl. the session revocation), `PasswordResetThrottleTest`, `LocalizedEmailTest`.

**Fixed in 1.16.0:** the earlier design generated, emailed and stored a new password straight from the unauthenticated request,
so anyone who knew an address could rotate that account's password once a minute (and, since the per-request revision check,
sign its owner out). A request changes nothing now - see above.

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`;
every bucket is installed by `plugins/RateLimits.kt`, which also owns the bucket names — today
`login`, `refresh`, `password-reset`, `password-reset-confirm`, `mfa`, `data-source-test`, `ready`, and the per-USER `reports` below): blank **follows the mode** — 10/min in production,
1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi`
idiom). Development is lifted because the e2e suite drives its logins from one host and would
otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence
and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and
is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`). The anonymous
`GET /api/v1/ready` (one pooled DB round trip per call) rides the `ready` bucket, 60/min in both modes, pinnable via
`security.rateLimit.readyPerMinute` (`$READY_RATE_LIMIT_PER_MINUTE`): the kubelet probes it every 5 s (12/min) and the
e2e global setup every 2 s (30/min), so probers stay far under it; `/api/v1/health` does no I/O and has no bucket.

`RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in
development mode.

**Code-read budget and permits** (`reports.codeMaxRows`, `reports.codeReadPermits`, `reports/CodeActivityBudget.kt`). The statement timeout bounds a query's time, not the rows the JVM then holds, and Report 21's `teamId=0` bucket reads every key of the sources, so any signed-in user could heap-exhaust a `web` process with a few concurrent whole-history requests. Two server-side bounds, neither trusting the client: a per-request ROW budget (default 100 000; every row statement is limited to one row past what is left, so an over-budget read is refused with a 400, never silently cut) on every GitLab code read (Report 21 and its drill, Compare people, its drill, Person trend), and a per-process pair of permits around Report 21 and its drill (a waiter holds no pooled connection and gets a 429 after the statement budget). For Report 21 and its drill the heap bound is the product, `permits x rows`; Compare people, its drill and Person trend take no permit, so they are bounded per request only (they read one or two people, or a team's peers, never every key of a source). Both are boot-validated (`requireConfigInt`, a malformed value refuses startup); the refusals are ordinary `ProblemDetail`s that carry no data, and raise no audit event (`observability.md`).

**Per-USER reports bucket** (`reports`, `security.rateLimit.reportsPerMinute`, `$REPORT_RATE_LIMIT_PER_MINUTE`) -- the one bucket keyed by
the authenticated caller's user id (the JWT `userId` claim) instead of the client IP, so a NAT'd office is not one bucket and changing IP
dodges nothing. The statement timeout bounds ONE query; this bounds the stream of them. ONE bucket per user covers every
`GET /api/v1/reports/**` operation (the 20 reports, Home and every `…/items` drill): `configureReportRoutes` mounts
`rateLimit(RateLimitName(RateLimits.REPORTS)) { … }` once, INSIDE `authenticate {}` around the whole report block, so the key is the verified
caller and an anonymous call stays a `401` that never reaches the bucket nor creates a limiter (`ReportRateLimitTest`; the `AnonymousAccessTest`
sweep is unaffected). A report route added outside that block escapes the bucket -- `ReportRateLimitTest` sweeps every `/reports/` operation
of the spec, so it fails the moment one does. **Follows the mode** like login: blank = 120/min in production (a session sends a handful a
minute), 1000/min in development, because the e2e suite shares ONE seeded admin across 4 Playwright workers whose derive pollers
(`e2e/tests/helpers.ts`, every 2 s) alone send 30/min each; a number pins it in either mode. Ktor's `RateLimit` (3.6.0) keeps one limiter per key and drops it
after its refill period, so memory is bounded by the accounts active within a minute (a few dozen, ADMIN-managed; only a verified token
reaches the key). The rejection is the generic `429` problem and the coalesced `rate_limit.exceeded` audit event
(bucket `reports`, plus `byUserId`; `observability.md`). `authenticate {}` runs BEFORE the bucket, so a throttled report request still runs the
per-request access statement (one pooled primary-key read, "JWT model"): the bucket bounds report queries, not that statement.

**Per-USER telemetry bucket** (`telemetry`, a fixed 30/min in every mode, `RateLimits.TELEMETRY`) -- the reports bucket's sibling for
`GET /api/v1/telemetry/current` and `/history` (ADMIN only): `configureTelemetryRoutes` mounts `rateLimit(RateLimitName(RateLimits.TELEMETRY))`
once INSIDE `authenticate {}` around both reads, so the key is the verified user id (`TelemetryRoutesTest`). The panel polls `current` once a
minute and fetches a history per expanded section, a handful a minute; each call is a few `ops` aggregations plus job-table reads, so the
bound is for a runaway client. The rejection is the generic `429` problem and the coalesced `rate_limit.exceeded` audit event (bucket `telemetry`, `byUserId`).

**Accepted exposure — capacity writes carry no bucket of their own** (domain-model A32). The two capacity `PUT`s
(`/api/v1/teams/{id}/capacity`, `.../sprint-calendars/{sprintId}`) are open to the team's MANAGER, not only ADMIN, and each REAL change
bumps the global `config_revision`, which enqueues a DERIVE per enabled connection (the team default) or for the
sprint's own connection (a sprint calendar; `metrics.md` "DERIVE fan-out per config change"). The amplification is bounded: the queue holds at
most one open DERIVE job per connection (further bumps coalesce into it), a no-op re-PUT bumps nothing, and every accepted write is
attributable and audited (`team.capacity_default_updated` / `team.sprint_calendar_updated`, with the previous figure and the grant
used). No per-user bucket is added; revisit if managers become numerous.

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in
`configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one
inverted-default flag**: `V5` seeds every existing user as MFA-disabled and `FlowUserService.create`
inserts the disabled row for every new user (admin create and test seeds both funnel through it),
so MFA is OFF until an admin removes the row via the features PUT (the per-user editor or the
`/feature-flags` bulk screen; mind the wholesale-replace semantics — a PUT whose disabled set
omits `MFA` ENABLES it, including the "empty array re-enables everything" idiom). With MFA
enabled, `POST /login` with correct credentials normally answers
`200 MfaChallengeResponse {mfaRequired, challengeId, expiresAt}` instead of tokens (the branch runs
AFTER `verifyPassword`, so nothing about enumeration or lockout changes; `login.success` is
deliberately NOT emitted); a 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is held in the
in-memory challenge store (config `security.mfa.codeTtlSeconds` default 300
`$MFA_CODE_TTL_SECONDS`, `security.mfa.maxAttempts` default 5 `$MFA_MAX_ATTEMPTS`; per-instance
like the throttles — a restart just means signing in again) and emailed in the user's stored
language (V1 `users.language`, EN fallback). Its hard `security.mfa.maxTracked` limit (default
10,000, `$MFA_MAX_TRACKED`) first drops expired challenges, then rejects new issuance with an
audited `429` at capacity; existing codes remain verifiable and no rejected code is emailed. A
failed or cancelled delivery discards its challenge so a retry can use that slot.
`POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in
every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first
(the `/refresh` precedent: current roles/flags; a user deleted in between → the uniform 401
`user_gone`); every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**,
reasons live only in the `login.mfa_failure` audit event; challenges are single-use and bound to
the credential revision verified at the password step; a subsequent password change/reset
invalidates the pending challenge. **Mail-less deployments fail closed**: on
`MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't
enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an
admin can always flip the flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*`
keys). Tests: `MfaChallengesTest` (pure store) + `MfaLoginTest` (route matrix); e2e `mfa.spec.ts`
drives the emailed code through Mailpit.

**Swagger/OpenAPI gate** (`plugins/Routing.kt`): `/openapi` (UI + spec) is served only in development
mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows
the mode, `"false"` hides it even in dev — AND only where the process serves the API: a `worker`-role
process mounts nothing but its probes (the mount lives in `configureRouting`, not `Http.kt`, because the role
is published by a later module; `RoleTest` pins the worker 404, and the CSP exemption follows the same
two conditions). Bearer auth cannot protect a browser-loaded UI (page
loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at
`/flow-users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96
bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt
hash and no response ever carries plaintext; users rotate it via the Change password page (the
existing `PUT /flow-users/{id}/password` rules). Deletion is the soft-delete convention plus two
guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted
(409).

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in
`flowusers/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-
depth) today, and every future create/update/import — so one mailbox is one account and a
padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index
stays byte-wise (all writes are canonical).

**Request body ceiling.** `RequestBodyLimit` (installed in `plugins/Http.kt`,
`MAX_REQUEST_BODY_BYTES` = 10 MiB) rejects oversized bodies with a 413 problem (mapped in
`plugins/ErrorHandling.kt`) before any receive/validation work — a memory-DoS backstop, not a
business rule; field-level `maxLength` validation rejects oversized values far earlier on ordinary
payloads. Declared in the spec on every body-taking operation (25 today; the spec is the source of the count) and covered by the one
`PayloadValidationTest` case.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate
payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting
oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥
`MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `flowusers/FlowUserRoutes.kt`
+ `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt
throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's
`verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. Keep new
validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered
by `PayloadValidationTest`.

**The Jira Cloud site allow-list (v0.2.0, `ingest/DataSource.kt`).** `jira.siteUrl` must match
`^https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.atlassian\.net$` exactly — origin only, no path, no
query, no port, no trailing slash — (the pattern and the reserved labels live in `infra/outbound/AtlassianSites.kt`, so the guard needs nothing from `ingest/`) enforced on every create/update (400 otherwise) and fixed as
the connection's identity (a changed value is `409`, not silently accepted; see
`.claude/docs/authorization.md`). This is the allow-list half of the outbound boundary; the
resolved-address guard below is defense in depth once the Jira client actually calls out (plan
commit 4).

**Outbound HTTP calls (SSRF posture — implemented, v0.2.0 plan commit 4).**
`infra/outbound/OutboundGuard.kt` ports Toadie's `UrlFetch.kt` `isBlockedAddress` (loopback,
site-local, link-local, any-local, multicast, IPv6 unique-local `fc00::/7`, CGNAT `100.64.0.0/10`,
`192.0.0.0/24`, benchmarking `198.18.0.0/15`, a NAT64 `64:ff9b::/96` address embedding a
non-public IPv4), adding two things: an IPv4-mapped IPv6 address is unwrapped to its IPv4 form first, and 6to4 `2002::/16`
and Teredo `2001:0::/32` are blocked (both pinned by `OutboundGuardTest`), as defense in depth. The PRIMARY
boundary is the host allow-list (`isAllowedJiraHost`): exactly `api.atlassian.com`, a genuine
`*.atlassian.net` tenant per the SAME shape/reserved-label check (`infra/outbound/AtlassianSites.kt`) `ingest/DataSource.kt` enforces at
create/update time (one source of truth), or — development mode ONLY — the configured
`jira.stubBaseUrl` host. `GuardedDns` (an `okhttp3.Dns`) re-checks the allow-list and re-resolves +
re-checks addresses on every NEW connection (not just once per request, since the Jira `HttpClient`
is long-lived and shared across every connection — pooled connections REUSE an already-checked
address rather than re-resolving on every call); the address rule is an injectable `addressAllowed(host, address)`
(default `publicAddressesOnly`, i.e. `!isBlockedAddress()`; the lookup is all-or-nothing, one refused answer fails it): the Jira
wiring answers `true` for the already-allow-listed stub host ONLY (the dev/compose/in-JVM stub is, by construction, a
loopback or docker-network-private address) and `publicAddressesOnly` for everything else — the allow-list still gates the stub
unconditionally, this only skips the address-SHAPE check for that one host. `isBlockedAddress` first unwraps an IPv4-mapped IPv6
address (`::ffff:a.b.c.d`, also an unfolded `Inet6Address`) to its IPv4 form, pinned by `OutboundGuardTest`. The Jira client also
checks every request's origin (`isAllowedJiraOrigin`: `https` on 443 to an allowed Jira host, or - development only - exactly the stub's
origin) and refuses a request carrying a caller-set `Host` header; this closes the IP-literal gap (OkHttp never hands a literal host
to `GuardedDns`) at the transport level, pinned by `JiraOriginGuardTest` through the client `configureJira` publishes. The guarded
`OkHttpClient` (`guardedOkHttpClient`) sets `Proxy.NO_PROXY`, `followRedirects(false)` (both at the
OkHttp engine level AND Ktor's own client-side `HttpRedirect` plugin — `followRedirects = false` and the
`HttpTimeout` in `infra/outbound/GuardedKtorClient.kt`, shared by the Jira and GitLab clients — checking-then-following would defeat the
address check either way),
`retryOnConnectionFailure(false)`, no cookies, no proxy authenticator. `fetch/SourceHttp.kt` layers a
bounded response read (Covenant's `ToadieGraphqlClient` shape, capped at `jira.maxResponseBytes`, default 8 MiB: "Response size cap" - wire bytes, plus a structural node ceiling (1/8 token per byte) and a depth ceiling (512 levels) checked before the parse, "Node and depth ceiling": a dense document's tree is at most ~108 MiB per response (it was ~230-300 MiB) and a deep one is a typed refusal, not a `StackOverflowError`; big parses are serialised, but several hostile trees can still pressure a 512 MiB worker whose OOM exits the process - narrowed, not closed; that bounds one PAGE — a stream
that holds several pages of one answer, or follows a host's paging, bounds the whole of it too: CHANGELOGS' and WORKLOGS' answer
ceilings, `jira-integration.md` "Oversized changelog/worklog batch" and "Worklog answer ceilings"; the REFERENCE, PROJECT_FIELDS, ISSUES and
RECONCILE loops carry page/row ceilings and a `nextPageToken` guard too, "Paging ceilings" - the shared `BoundedAnswer`/`PageCeiling`/`RunBudget`
lever in `fetch/Ceilings.kt`) and exponential backoff with jitter (`Backoff`) on top. Every guard rejection is audited as
`outbound.blocked` (scheme + host ONLY — never the full URL, which may embed a token). **A DNS failure is NOT a guard
rejection**: when the resolver throws `UnknownHostException` or answers with no address for an ALREADY allow-listed host,
`GuardedDns` throws a plain `UnknownHostException(host)` (never a `BlockedHostException`), audits nothing and logs ONE WARN
(host only, no stack trace). No security property is in play (the host passed the allow-list, no blocked address was seen), and
a lost DNS answer is routine on a VPN/forwarding resolver — reporting it as a block made one transient lookup a fatal,
unretried `BLOCKED_HOST` that failed a 17-minute SYNC and sent whoever read the audit stream chasing a block that never
happened. It is an `IOException`, so `SourceHttp` retries it with the normal backoff, and every retry re-runs the allow-list
check and the resolve-check-connect sequence. The allow-list check still runs BEFORE any resolution, so a disallowed host is
never resolved. Pinned by `OutboundGuardTest`.
`jira.stubBaseUrl` is fail-closed: production startup refuses a non-blank value (`jira/Jira.kt`,
pinned by `OutboundGuardTest`'s boot test) — the same shape as the crypto-key/mail-transport
checks. A Jira Cloud API host is always public, so this guard is a defense-in-depth backstop; the
primary trust boundary is the scoped, read-only API token (see "Encryption at rest" below, whose
first consumer that token is). See `.claude/docs/jira-integration.md` for the client/auth/rate-limit
shape built on top of this guard.
A hostile host behind the allow-list is also bounded by the CONFIGURATION: the worker's boot-time heap fit check
(`jira/JiraHeapFit.kt`, every mode, not only production) refuses a role that runs the worker unless its old generation holds
`ingest.workerSlots` slots, each the largest of a worst-case response tree (`jira.maxResponseBytes` / 8 nodes), a PROCESS slot and a CHANGELOGS / WORKLOGS
answer-text slot (those two convert every page under the big-parse lock, so only one slot at a time adds a tree on top of its text), plus the bodies in flight and a reserve (`jira-integration.md` "Node and depth ceiling", "Stored-size ceilings", `JiraHeapFitTest`); a
`web` role only warns. The STORED text later re-read into trees is bounded since 1.24.1: the raw writers refuse an item over a stored-size
ceiling (`jira.maxStoredPayloadBytes`, a history, a worklog; measured as the rendered size, so an exponent number cannot hide plain-digit
bloat), PROCESS claims pages by stored bytes (`jira.processPageBytes`), whole entity kinds are read only in chunks of that size, and the `web` role's
raw-issue inspector answers 413 for an issue over it, embeds stored text unparsed and serves two issues at a time.

**A self-managed GitLab host (operator-only allowance, `infra/outbound/SelfManagedHosts.kt`, `Cidr.kt`, `CaBundle.kt`; `gitlab/GitLab.kt`).**
A self-managed GitLab usually lives on a private address, which the guard above refuses by design, so two operator settings widen
it for that one connector. Nothing a user or an admin types can widen them: a data source's base URL is validated against them
(`gitlab/GitLabBaseUrl.kt`), never the reverse. The rule, in `SelfManagedHosts.addressAllowed`:

- `GITLAB_ALLOWED_HOSTS` (`gitlab.allowedHosts`): exact lowercase DNS names, `host` or `host:port` (no port = 443). No wildcard (a
  wildcard turns a typo or a subdomain takeover into an allowed internal target), no IP literal in any notation (the JDK reads
  `2130706433` or `0x7f.1` as an address, and OkHttp never consults the DNS guard for a literal), no scheme/path/query/userinfo
  (credentials must never sit in a config value that can reach a log). Empty = the GitLab kind is off and no client is built.
- `GITLAB_ALLOWED_CIDRS` (`gitlab.allowedCidrs`): the private ranges a LISTED host may resolve to. Boot refuses `/0`, IPv4 wider than /8
  and IPv6 wider than /32 (a typo cannot open a whole private class), any range that overlaps loopback (`127/8`, `::1`), link-local
  (`169.254/16`, which holds the cloud metadata address `169.254.169.254`, and `fe80::/10`), any-local (`0/8`, `::`, `::/96`), multicast
  (`224/4`, `ff00::/8`), 6to4 (`2002::/16`) or Teredo (`2001:0::/32`) space, an IPv4-mapped range (`::ffff:0:0/96`: write the IPv4 range)
  or a NAT64 range (`64:ff9b::/96`: NAT64 addresses are never admitted), a range with host bits
  set, a malformed entry (the address part is matched against a literal-only grammar before it reaches the JDK, so a typo is never a DNS
  lookup), and CIDRs without hosts. Every error names the key and the entry, except an entry that is not strictly host-shaped
  (`host`, `host:port`) or CIDR-shaped (hex digits, `.`, `:`, `/length`) - an `@`, `?`, `#`, `*`, a path, a space, a `%` - which is named by
  position only so `user:secret@host` never reaches a log.
- Per lookup: loopback, link-local, any-local, multicast, `0.0.0.0/8`, `::/96`, 6to4 and Teredo are refused for EVERY host whatever the CIDRs say;
  any other address that is not blocked today (public) is allowed for every host as before; any other blocked address (private,
  unique-local, CGNAT, ...) is allowed only for a listed host and only inside a CIDR. An IPv4-mapped address is judged as its IPv4 form.
  Unlisted hosts keep exactly the Jira rule. A mixed answer (one allowed address, one refused) fails the whole lookup, audited
  `outbound.blocked`; every NEW connection resolves and checks afresh, so a DNS-rebinding answer is judged when it arrives.
- Request origin (`guardedOkHttpClient(originAllowed = ...)`): DNS only sees a hostname, and OkHttp skips DNS for an IP-literal URL, so the
  GitLab client also checks every request's (scheme, host, port) against the list before sending: `https` to a listed `host[:port]` only
  (an entry with a port means that port). Plain `http`, another port, any IP literal and any request carrying a caller-set `Host` header
  (it would name one host to the guard and another to the server) are `outbound.blocked`.
- `GITLAB_CA_BUNDLE`: a PEM file of X.509 certificates added to the GitLab client's trust store ALONGSIDE the JVM default CAs (a fresh
  in-memory KeyStore holding both, a standard PKIX trust manager over it). The Jira client keeps the platform trust. Boot refuses an
  unreadable, over-1-MiB, malformed or certificate-less file, and a bundle without listed hosts. There is no skip-TLS, trust-all or hostname-verifier switch anywhere
  (`GitLabCaBundleTest` pins the `gitlab.*` key set and scans the client code).
- **What the commit streams store (PR 7).** `raw.gitlab_commits`: the commit's first line (`title`, <=255), author name and email, authored/committed
  time, parent count and the `stats` totals; `raw.gitlab_commit_files`: the path, old path (renames), lines and flags. No message body, committer, diff text or
  payload is stored, and the diff is counted inside the response transform and dropped. The ref of a commit listing goes out as a query value and a commit
  SHA as a path segment only after it matches `^[0-9a-f]{40}$|^[0-9a-f]{64}$`, never as concatenated text.
- Kept from the Jira client: no redirects (OkHttp and Ktor), no proxy, no connection-failure retry. `gitlab.stubBaseUrl` (`GITLAB_STUB_BASE_URL`)
  is development-only and production refuses a non-blank value. `gitlab.maxResponseBytes` defaults to `min(8 MiB, jira.maxResponseBytes)` and an
  explicit value may not exceed `jira.maxResponseBytes`. That keeps a GitLab response tree inside the per-slot tree `JiraHeapFit` sizes
  from the Jira ceiling. In-flight GitLab bodies need no term of their own: GitLab's `SourceHttp` draws on the SAME permit pool as Jira's
  (`fetch/OutboundPermits.kt`, published by `configureJira`), so the fit's `jira.maxConcurrentRequests x jira.maxResponseBytes` term already
  covers them (`gitlab-integration.md` "Outbound calls"). In k8s the egress NetworkPolicy still excludes the private ranges: a private GitLab needs its own
  additional egress rule (the policy is the second layer; the guard is the first). The merge-request diff stats are the ONE `POST` a GitLab connection makes
  (`<origin>/api/graphql`, a fixed read-only query whose only inputs are a project path and at most 50 iids, sent as variables): it goes through the same guard, permit and ceilings as
  every GET, which is method-agnostic. What the merge-request streams keep is a whitelist - title, branch names, ids, times, action codes - never a description or a note body (`GitLabMrStoreTest`, `GitLabMrStreamsTest`).
- Pinned by `SelfManagedHostsTest` (parsing, every boot refusal, the address matrix), `GitLabOutboundGuardTest` (rebinding, mixed answers,
  redirect, unlisted host audit, IP literal, port, proxy, Host header), `GitLabBaseUrlTest`, `GitLabCaBundleTest`, `GitLabBootTest` and the
  `PackageBoundaryTest` rules (`gitlab` never imports `jira`, `metrics` or `reports`; `infra/outbound` imports no feature).

What pins the transport (beyond the allow-list/address tests above): `OutboundTransportTest` runs against a LOCAL
socket server only. The response/connection release in `fetch/SourceHttp.kt` (`prepareRequest {}.execute {}`) is
pinned with the leak window forced open — the server sends headers plus a first chunk and HOLDS the rest of a
10 MB body, so a 503 must surface as `UPSTREAM_UNAVAILABLE (503)` and an oversized body as `LIMIT_EXCEEDED`
(the old `client.request()` buffers the whole body and ends in `TIMEOUT`), and the server must see the client
close the socket (the deterministic "released, not leaked" signal; `OutboundGuardTest`'s event-listener count
alone never failed with the old code). A sibling negative control asserts the old `client.request()` pattern DOES
time out against that server — if it ever stops, the two tests no longer discriminate. `DirectSocketFactory`:
every self-resolving `createSocket` overload is refused; its socket never consults the JVM `ProxySelector` (a plain
`Socket()` does — the control), and the guarded client connects to exactly the address `GuardedDns` returned for an
unresolvable `.invalid` hostname (one resolution, `Proxy.NO_PROXY`, the Host header intact). `fastFallback(false)`
is a configuration assertion by choice: its effect only shows with several resolved addresses and a slow earlier
one, which loopback cannot stage without timing races.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when
`http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is
single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller
exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config
`http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders`,
configured to honour ONLY the canonical `X-Forwarded-Host`/`X-Forwarded-Proto` (Ktor's defaults
also read `X-Forwarded-Server`/`X-Forwarded-Protocol`/`X-Forwarded-SSL`/`Front-End-Https`, which a
proxy that sets just the canonical ones passes through from the client untouched — Lettuce's
v3.6.2 finding, ported) and never `X-Forwarded-Port` (a client-supplied non-numeric value used to
throw a 500 out of `CallSetup` before any route ran; nothing reads it, since the scheme-derived
default port is all the HTTPS redirect needs), so rate-limit buckets key on the real client IP and
the HTTPS redirect sees the real scheme; the proxy must set (and strip client-supplied)
`X-Forwarded-For`/`X-Forwarded-Proto`. **`X-Forwarded-For` is folded before Ktor ever reads it**:
Ktor's `XForwardedHeaders` resolves a for-header via `Headers.get(name)` — the FIRST header LINE
only — so a proxy that APPENDS a fresh `X-Forwarded-For` line instead of merging into one the
client already sent (HAProxy's `option forwardedfor`) would leave the client-supplied first line as
the trusted value. `resolveForwardedForOrigin` (`plugins/Http.kt`) runs at the `Setup` phase —
strictly before `XForwardedHeaders`' own `Plugins`-phase `onCall` — reads every
`X-Forwarded-For` header line via `getAll`, folds them into one hop-ordered list (RFC 2616: same-
name header fields may be combined by joining their values with a comma, in the order received),
and resolves the trusted hop itself directly into the call's `MutableOriginConnectionPoint`;
`XForwardedHeaders` is then configured with an empty `forHeaders` so it never overwrites that
result with its own unfolded, first-line-only read. The hop selection itself is unchanged: read
from the END of the combined list, keyed on `HTTP_PROXY_HOPS` (config `http.proxyHops`,
boot-validated ≥ 1 via `requireConfigInt` — a config error, not a runtime concern): 1 (the default,
one TLS-terminating proxy that APPENDS) trusts the value the last proxy wrote; N skips the N-1
addresses trusted proxies appended after the client's — never the first value, which is
client-supplied and spoofable. A hop count in excess of what the request actually carries falls
back to the last available value (the same fallback Ktor's own `useLastProxy()`/
`skipLastProxies()` apply) rather than throwing. Off by default because honoring these headers
from direct clients lets them spoof both. Covered by `ForwardedHeadersTest`; the multi-line fold
itself needs a real Netty engine to pin (`RawForwardedForLinesTest` writes the raw HTTP/1.1
request by hand — ktor-client's own request writer folds repeated `header()` calls into one wire
line before send, so `testApplication`'s client cannot reproduce two genuinely distinct lines).

CSRF install is gated behind `security.csrf.enabled` (default **`false`** in `application.yaml`,
env-overridable via `SECURITY_CSRF_ENABLED`); the configured `originMatchesHost()` +
`allowOrigin("http://localhost:8084")` + `checkHeader("X-CSRF-Token")` combo is unsatisfiable from
both the Ktor test client and the dev SPA on `:5176`, and CSRF protection is anyway moot for this
app's bearer-JWT auth model — browsers do not auto-attach `Authorization` headers, so cross-site
forms cannot forge an authenticated request. Re-enable only if you move to cookie-based session
auth and fix the allow-list accordingly.

**Default admin.** Migration `V3__seed_admin.sql` inserts a single bootstrap administrator on
first boot: `admin@flow.local` / `changeme` (role `ADMIN`), idempotent via `ON CONFLICT DO
NOTHING`. The migration is kept **unchanged** (dev + e2e depend on it; checksums must not change)
— production neutralizes it at startup via the bootstrap above. There are no demo seed users.
**Kubernetes secrets** live in the `flow-secrets` Secret in the `flow` namespace
(`k8s/secret.yaml` is a placeholder template — create the real one out-of-band with the
`kubectl create secret generic` command in its header; both the `app` and `worker` Deployments consume it via
`secretKeyRef`).

**Probes and the plain-HTTP crash-loop** (`k8s/web-deployment.yaml` and `k8s/worker-deployment.yaml`,
ported from Toadie): every
probe (`startupProbe`/`readinessProbe`/`livenessProbe`) sends `X-Forwarded-Proto: https` — the
header the TLS-terminating ingress sets, which `HTTP_BEHIND_PROXY`/`HTTP_PROXY_HOPS` above makes
the app trust. Without it, production mode answers the kubelet's plain-HTTP probe request with a
301 to `https://<pod-ip>/`, which the kubelet FOLLOWS (same host) into a refused `:443` — every
probe fails and the pod crash-loops (Lettuce's v3.6.1 incident, reproduced in production).
`startupProbe` (30 x 5s = ~150s) covers JVM boot + Flyway migrations before the liveness clock
starts, so a slow first boot cannot trigger a restart.

**Kubernetes pod and network hardening** (`k8s/*.yaml`, checkup A13, validated by ci.yml's `k8s-static`
job — `kubeconform -strict` over the raw manifests and over what `k8s/apply-local.sh` renders):

- **Pods.** All three (`app`, `worker`, `postgres`) set `automountServiceAccountToken: false` — nothing
  calls the Kubernetes API, so no token is mounted. `app`/`worker`: uid 10001, `runAsNonRoot`, seccomp
  `RuntimeDefault`, read-only root filesystem (only `/tmp`; the AppCDS archive `/app/cds/flow.jsa` is a root-owned 0444 file baked into the image and only ever read, build-times.md WHY 27), no privilege escalation, `drop: [ALL]`.
  `postgres` runs the same way minus the read-only root filesystem: `runAsNonRoot` as the alpine image's
  own `postgres` user (**uid/gid 70** — 999 in the Debian image, so change `runAsUser`/`runAsGroup`/
  `fsGroup` together with the image variant), `fsGroup: 70` with `fsGroupChangePolicy: OnRootMismatch`,
  seccomp `RuntimeDefault`, `allowPrivilegeEscalation: false`, `drop: [ALL]` (a non-root start needs no
  capability: the entrypoint's chown/gosu step only runs as root). `readOnlyRootFilesystem` stays off for
  postgres — it writes its socket/lock files under `/var/run/postgresql` and temp files under `/tmp`.
  Verified on OrbStack (local-path storage): a fresh init as uid 70, a restart on the same data, and an
  in-place upgrade of a volume first initialised by the old root-start manifest all come up with the
  data intact (the data directory is already `postgres`-owned after a root start).
- **`k8s/network-policies.yaml`.** `default-deny-all` (ingress AND egress for every pod in `flow`), then:
  `postgres` accepts 5432 only from `app`/`worker`; `app` accepts 8084 (see the open decision below);
  `app` and `worker` may egress to `postgres:5432`, to cluster DNS (kube-system `k8s-app=kube-dns`, 53
  UDP/TCP) and to public addresses on 443 (the Jira Cloud gateway), 587/465/25 (SMTP) — RFC 1918,
  link-local (cloud metadata `169.254.169.254`) and CGNAT ranges excluded, so a compromised pod cannot
  reach other namespaces or cluster services. The `worker` accepts nothing from the network (kubelet
  probes come from the node, which Kubernetes always allows). Things that need their own extra policy:
  an in-cluster/private SMTP relay, an OTLP collector (`OTEL_*_EXPORTER=otlp`; the default is console),
  the dev-only Jira stub (`JIRA_STUB_BASE_URL` — a private address). **Cluster assumptions:** DNS is
  matched as pods labelled `k8s-app: kube-dns` in the `kube-system` namespace (stock CoreDNS); a
  NodeLocal DNSCache (link-local resolver address) or OpenShift (`openshift-dns` namespace, different
  labels) needs the DNS rule adjusted, or every lookup times out; SMTP submission on port 2525 and any
  IPv6 destination are not covered (the `ipBlock` is IPv4 only) and need their own rule. Enforcement needs a CNI that
  implements NetworkPolicy (OrbStack's local cluster does: verified — app→postgres and app→:443 allowed;
  app→:80, app→private ranges, a stranger pod's DNS/egress, and any outside caller of `worker:8084`/
  `postgres:5432` blocked). A CNI that does not enforce leaves the objects inert, which is harmless. New
  pods take a few seconds to be programmed, so a very fast first connection can fail once — the
  `startupProbe` window absorbs it.
- **Still open — the front door (decision pending).** `k8s/app-service.yaml` is an OrbStack
  `LoadBalancer` and the manifests default to the production posture (`KTOR_DEVELOPMENT=false`,
  `HTTP_BEHIND_PROXY=true`), which assumes a TLS-terminating proxy that sets and overwrites
  `X-Forwarded-For`/`-Proto`. The reference ships no Ingress: over the bare LB, a plain-HTTP request gets
  the production HTTPS redirect and `X-Forwarded-*` is client-controlled. Either (a) add a TLS Ingress +
  `type: ClusterIP`, or (b) keep the LB and document a local overlay with `KTOR_DEVELOPMENT=true` /
  `HTTP_BEHIND_PROXY=false`. Whichever is chosen must also tighten `allow-app-ingress`, whose `from` is
  deliberately absent today (the 8084 port is its only restriction), to the ingress controller's
  namespace.

**Log hygiene.** Every data class that carries a password, token, MFA code, password hash or signing key
(`LoginRequest`, `LoginResponse`, `MfaVerifyRequest`, `RefreshRequest`, `LogoutRequest`, `FlowUser`,
`FlowUserCreateRequest`, `PasswordUpdateRequest`, `PasswordResetConfirmRequest`, `JwtConfig`, `JiraConnectionRequest`,
`DataSourceService.StoredJiraConnection`, the MFA challenge types, `IssuedToken`) overrides `toString()` and prints each secret
through `maskedSecret` (`infra/Secrets.kt`: `***`, or `null` when absent), so templating one into a log line or an
exception message never reveals it; a new secret-bearing class does the same. Pinned by `SecretToStringTest`
(and `DataSourceRoutesTest` for the Jira request). `infra/db/Flyway.kt`'s startup log line renders the operator-supplied JDBC URL
through `jdbcUrlForLogging()` — `host:port/db` only, no userinfo or query string — so a
`jdbc:postgresql://host/db?user=...&password=...` form never lands a credential in the log
(`.claude/docs/observability.md`, "never log secrets"); covered by `FlywayLogTest`. `POST
/api/v1/logout`'s malformed-body debug line (`auth/AuthRoutes.kt`) logs only the parse failure's
exception CLASS NAME, never the throwable — the cause chain can embed a body excerpt (kotlinx's
decode-error message), the same `errorType`-only rule as `login.mfa_send_failed`/
`password_reset.send_failed`; covered by `LogoutTest`.

**Data minimization in the raw store.** The raw store keeps the TRIMMED Jira payloads (`jira/JiraPayloadTrim.kt`, `persistence.md` "The Jira
raw store (V10)" and "The Jira changelog/worklog raw store (V11)"). Every embedded user - a changelog history's `author`, a worklog's
`author`/`updateAuthor`, an issue's `assignee`/`reporter`/`creator` - is stored as `{accountId, displayName, accountType}` only, so the
`emailAddress`, avatar URLs and time zone Jira embeds in hundreds of thousands of copies are not kept (the full user stays once, as the
`USER` entity). The free text of an issue is not stored either: `fields.comment`, `fields.description`, `fields.attachment`, Jira's
embedded `fields.worklog` copy, a worklog's `comment` and the changelog items of a description edit. **What this does NOT cover:** the
`customfield_*` values stay verbatim (a user picker or a rich-text custom field still holds whatever Jira sends), and so does every other
system field (the summary, labels, ...). Rows written before the trim existed keep their old content until it is rewritten; covered by
`JiraUserReferenceSlimmingTest` and `JiraPayloadTrimTest`.

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of
the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null.
`mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including
reset links and MFA codes**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to
start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host
refuses startup in any mode), or `disabled` (the Docker image default via
`ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The
compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8028` — 8025/8026/8027 belong
to sibling stacks); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from
`flow-secrets`. Consumers: self-service password reset and email MFA. The recipient-language
content layer (`LocalizedText`) lives beside the transports; `mail.appUrl` is validated here too (above). Tests:
`MailTransportTest` (the transport matrix + both refusals + LogMailer delivery via the
`ch.nokillswit.mail` LogCapture); production-mode boot tests must override `mail.transport` with
`"disabled"`, since the dev-default `log` transport is refused in production.

**Encryption at rest** (`infra/crypto/`, Lettuce's, ported verbatim; its first consumer is the
Jira API token, see the end of this section). Sensitive columns are encrypted application-side with AES-256-GCM (`FieldCipher`: a
fresh 12-byte nonce per value, 128-bit tag, envelope `enc:v1:<base64(nonce||ciphertext)>`), so a
database-level attacker — SQL access, `pg_dump`, a stolen volume or backup — sees ciphertext; the
key lives with the app (`DATA_ENCRYPTION_KEY`, 64 hex chars from `openssl rand -hex 32`; compose
ships a burned demo key, k8s reads `flow-secrets`), never in the database. **Losing the key loses
every encrypted value** — back it up apart from the DB. Rotation: set the OLD key as
`DATA_ENCRYPTION_KEY_PREVIOUS` (decrypt-only fallback — a wrong-key attempt fails cleanly on GCM's
tag, then the previous key is tried) and the new one as `DATA_ENCRYPTION_KEY`, boot once (the
bootstrap backfill re-encrypts every row under the current key), then remove the previous key.
Rows written before a column was encrypted (legacy plaintext, returned unchanged by `decrypt`)
are encrypted once at the next boot by the same backfill (`reencryptRows`, selecting through the
ONE sanctioned SQL predicate over an encrypted column: `notLike "enc:v1:%"`). A service owning
encrypted columns implements `EncryptedAtRest` and is registered in `infra/db/Bootstrap.kt`'s
`encryptedAtRestServices()` list (`DataSourceService` is the first and, today, only entry — see
below) — never remove a registration once one lands (a rotation would strand that feature's rows). **Never filter or sort on an encrypted column in
SQL** — ciphertext carries no order or equality (every value has its own nonce). **The v0.2.0 Jira
API token is the first consumer, landed**: `source_connections.secret` (V8,
`ingest/DataSourceService.kt` implements `EncryptedAtRest`, registered in
`infra/db/Bootstrap.kt`'s `encryptedAtRestServices()`) — write-only end to end: `POST`/`PUT
/api/v1/data-sources` accept `jira.apiToken` but no response, and no audit event, ever returns it;
every response instead carries `jira.hasApiToken`. **The GitLab personal access token is the second consumer** (PR 6, the same
`secret` column and the same service, no new registration): `gitlab.token` is write-only the same way (`gitlab.hasToken` in responses, never in
an audit line, masked in `GitLabConnectionRequest`/`StoredGitLabConnection`/`ConnectionTarget.GitLab` `toString`, pinned by
`SecretToStringTest`), decrypted only for the connector and sent as the `PRIVATE-TOKEN` header; a token is refused unless every scope starts with `read_` or is `admin_mode` (an allow-list: `api`, `sudo`, `write_*`, `create_runner`, `k8s_proxy`, ... are all
too broad) at Test connection and at every SYNC (`TOKEN_SCOPE_TOO_BROAD`; an inactive one is `TOKEN_INACTIVE`; a token-info answer missing a field is
`INVALID_RESPONSE`, never a pass). Tests: `FieldCipherTest` (roundtrip, fresh
nonces, legacy passthrough, tamper, wrong key, rotation, malformed keys), `CryptoBootTest`,
`EncryptedAtRestBootTest` (seeds a connection, boots again with a rotated key, the token still
decrypts under the new key alone).

**Trusted PostgreSQL extensions.** `btree_gist` (v0.3.0 M1 commit 3, `V15__create_metrics_config.sql`
— backs `metrics.team_membership`'s overlap-exclusion constraint) joins `unaccent` (V4) as a
contrib extension trusted since PG13: `CREATE EXTENSION IF NOT EXISTS` needs only `CREATE` on the
database, no superuser — see `.claude/docs/persistence.md`'s "The `metrics` schema — configuration
(V15)".

### Telemetry

`telemetry/` (`observability.md` "Telemetry") records Flow's own vital signs into `ops.*` and serves them to ADMINs only (`GET /api/v1/telemetry/current`
and `/history`, `authorization.md`; per-user bucket `telemetry`, 30/min). What it discloses, and to whom — every ADMIN, nobody else (a USER gets the
uniform `403`; the worker role serves no API at all):

- **Process identity:** the role, the pod / host name (`HOSTNAME`, up to 100 control-free characters), the JVM start, the image's build version and
  commit, the processor count, the heap maximum and the container memory limit - and the live memory, CPU, thread, GC and connection-pool figures.
  NEVER JVM arguments, environment variables, system properties, request paths with identifiers or any project data (pinned: the `ops` tables hold
  numbers and these labels only).
- **Data-source names and health:** every not-yet-purged connection's id, name (including soft-deleted ones awaiting their PURGE), enabled flag,
  size estimate, issue count, last-sync freshness and error code, queue depth, Jira 429 / back-off figures and DERIVE timings - read through
  `DataSourceService.connectionHealth()`, which selects those columns ONLY (never the site URL, email, project keys, token or profile).
  `last_sync_error_code` is a closed vocabulary (`RUN_FAILED`, `RETRIES_EXHAUSTED`, `CONNECTOR_UNAVAILABLE`, `TOKEN_SCOPE_TOO_BROAD`, `TOKEN_INACTIVE`, and the ceiling-trip codes `LIMIT_EXCEEDED`, `TIME_EXCEEDED`; anything else is stored as `RUN_FAILED`), never free text; so is `pause_reason` (the two trip codes, a CHECK), which the panel shows with `pausedAt`.
- **Route usage:** the normalised report routes (`/api/v1/reports/...`, path parameters as written, never an identifier) with their request counts,
  latencies and 5xx / 429 counts. The telemetry routes themselves and the probes are excluded.

**Metric-name cardinality is bounded.** Ktor's `DropwizardMetrics` (3.6) names a meter after `request.uri` - query string included - for every response it
could not route and for every second response of a call (the StatusPages re-answer of the RateLimit plugin's bodiless `429`, routing's `405`), and a
registered Dropwizard instrument is never evicted: an unauthenticated client (`POST /api/v1/login?x=<random>` past the login bucket, random unrouted
paths or methods) could mint one meter per distinct URI until the heap is gone, and the periodic `Slf4jReporter` would print each of them, query
strings included. So `plugins/Monitoring.kt`'s `TelemetryRegistry` registers NO `ktor.calls.` name containing a `?` (the caller gets a detached,
unregistered instrument), caps the whole `ktor.calls.` family at 2,000 registered names (past it: detached instruments and one WARN), and the reporter
filters out any name with a `?` as defence in depth. Pinned by `TelemetryRegistryTest` (a flood of unrouted, 405 and rate-limited requests with varying
query strings registers none). The sampler reads the registry's timers and meters only, so detached instruments cost it nothing.

### Not yet ported

Nothing remains on the security list beyond the forward SSRF guidance above; new subsystems
arrive with their own section here.
