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
- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including
  generated passwords) into the application log — permitted with a warning in development,
  **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode.
  See "Outbound email" below.
- **Data-encryption key** (`infra/crypto/Crypto.kt`, module `configureCrypto`, right after
  `configureMail` and BEFORE `configureDatabase`): blank or the burned repo-committed dev default
  → warn in development, **refuse to start** in production; a malformed key (not 64 hex chars —
  the k8s template placeholder included) refuses in ANY mode. See "Encryption at rest" below.
  Covered by `CryptoBootTest`; every production-mode boot test overrides
  `security.encryption.key` with `strongEncryptionKey()`.

**JWT model** (`auth/Tokens.kt` + `plugins/Security.kt`): login issues an access/refresh **pair**,
both carrying `email`, `userId`, a `roles` array, a `typ` claim (`"access"`/`"refresh"`), and a
random `jti`. The `jwt {}` verifier requires audience (`flow-api`), issuer
(`http://0.0.0.0:8084/` by default), `typ == "access"`, and a PRESENT `jti` absent from the
DB-backed blocklist (a jti-less token could never be revoked, so both the verifier and `/refresh`
reject it outright) (`auth/TokenBlocklistService.kt`, table `revoked_tokens` — `/logout` writes
it, the revoke path prunes expired rows). Blocklist verdicts are served through a 30-second
in-memory cache (`REVOCATION_CACHE_TTL_MS`) so the per-request check is not a DB round-trip;
`revoke()` seeds its own instance, so on this single-replica deployment a logout is visible
immediately and the TTL only bounds staleness across a restart (covered by
`TokenBlocklistCacheTest`). **A blocklist lookup that THROWS inside the JWT `validate` block
(database unreachable, pool acquire timeout) is an outage, not a bad token:** ported from
Lettuce, `validate` runs the cache-missed lookup through `catchingFailures` (`infra/Failures.kt`)
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
running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP
revocable through `/logout` or a password change/reset — so the real control is keeping foreign
script out. That control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest`: a strict CSP (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
`object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` — the whole string is pinned
verbatim in `ServerTest`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`. Every `/api/` JSON and problem+json answer additionally carries
`Cache-Control: no-store` (`plugins/Http.kt`'s `CachingHeaders`, pinned by `ServerTest` together
with the unchanged caching of the SPA's static assets), so per-user data never lands in a browser
or proxy cache.

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
account exists (no enumeration signal). A success resets the counter. The in-memory, per-instance
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

**Self-service password reset** (`POST /api/v1/password-reset`, in `auth/AuthRoutes.kt`; SPA:
"Forgot password?" on `/login` → `/reset-password`): body `{email}`; normally `202` for a
well-formed request regardless of account existence (the lookup/generate/send/store work runs
**asynchronously** after the response, so latency is uniform too). If the account exists, a new
16-char password is generated server-side (`generatePassword` in `auth/Passwords.kt`, 96 bits),
the email is sent **first** and only then the bcrypt hash stored via `UserService.updatePassword`
(a delivery failure leaves the old password working); the atomic `credential_revision` increment
invalidates outstanding refresh tokens and pending MFA challenges. Throttled per submitted email —
one request per `security.passwordReset.minIntervalSeconds` (default 60,
`$PASSWORD_RESET_MIN_INTERVAL_SECONDS`) — uniformly for existing and unknown addresses
(`auth/PasswordResetThrottle.kt`, in-memory/per-instance like the lockout). Its hard
`security.passwordReset.maxTracked` limit (default 10,000, `$PASSWORD_RESET_MAX_TRACKED`)
reclaims elapsed cooldowns but never evicts a fresh one; saturation rejects new identities with an
audited `429` before any email worker starts. The same 429 response applies to known and unknown
addresses. A per-IP `RateLimit` bucket (`security.rateLimit.passwordResetPerMinute`,
`$PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` — blank follows the mode: 5/min production, 100/min
development) also applies. `503` when the deployment has no outbound email
(`MAIL_TRANSPORT=disabled`). A soft-deleted account is unknown here by construction
(`findWithIdByEmail` filters active rows). The email renders through the `LocalizedText` catalog
(`infra/mail/PasswordEmail.kt` + `auth/PasswordResetEmail.kt`) in the RECIPIENT'S stored language
(V1 `users.language`, EN fallback), includes a sign-in link only when `mail.appUrl` is set, and
warns the recipient that their previous password no longer works. Tests:
`PasswordResetThrottleTest` + `LocalizedEmailTest` (unit) + `PasswordResetTest` (route, incl. the
full email→login roundtrip via a `ListAppender` on the `ch.nokillswit.mail` logger and the
send-before-store delivery-failure case).

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`;
every bucket is installed by `plugins/RateLimits.kt`, which also owns the bucket names — today
`login`, `refresh`, `password-reset`, `mfa`, `data-source-test`): blank **follows the mode** — 10/min in production,
1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi`
idiom). Development is lifted because the e2e suite drives its logins from one host and would
otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence
and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and
is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`).
`RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in
development mode.

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in
`configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one
inverted-default flag**: `V5` seeds every existing user as MFA-disabled and `UserService.create`
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

**Swagger/OpenAPI gate** (`plugins/Http.kt`): `/openapi` (UI + spec) is served only in development
mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows
the mode, `"false"` hides it even in dev. Bearer auth cannot protect a browser-loaded UI (page
loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at
`/users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96
bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt
hash and no response ever carries plaintext; users rotate it via the Change password page (the
existing `PUT /users/{id}/password` rules). Deletion is the soft-delete convention plus two
guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted
(409).

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in
`users/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-
depth) today, and every future create/update/import — so one mailbox is one account and a
padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index
stays byte-wise (all writes are canonical).

**Request body ceiling.** `RequestBodyLimit` (installed in `plugins/Http.kt`,
`MAX_REQUEST_BODY_BYTES` = 10 MiB) rejects oversized bodies with a 413 problem (mapped in
`plugins/ErrorHandling.kt`) before any receive/validation work — a memory-DoS backstop, not a
business rule; field-level `maxLength` validation rejects oversized values far earlier on ordinary
payloads. Declared in the spec on the body-heavy operations (users create today) and covered by
`PayloadValidationTest`.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate
payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting
oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥
`MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `users/UserRoutes.kt`
+ `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt
throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's
`verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. Keep new
validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered
by `PayloadValidationTest`.

**The Jira Cloud site allow-list (v0.2.0, `ingest/DataSource.kt`).** `jira.siteUrl` must match
`^https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.atlassian\.net$` exactly — origin only, no path, no
query, no port, no trailing slash — enforced on every create/update (400 otherwise) and fixed as
the connection's identity (a changed value is `409`, not silently accepted; see
`.claude/docs/authorization.md`). This is the allow-list half of the outbound boundary; the
resolved-address guard below is defense in depth once the Jira client actually calls out (plan
commit 4).

**Outbound HTTP calls (SSRF posture — implemented, v0.2.0 plan commit 4).**
`infra/outbound/OutboundGuard.kt` ports Toadie's `UrlFetch.kt` `isBlockedAddress` VERBATIM (loopback,
site-local, link-local, any-local, multicast, IPv6 unique-local `fc00::/7`, CGNAT `100.64.0.0/10`,
`192.0.0.0/24`, benchmarking `198.18.0.0/15`, a NAT64 `64:ff9b::/96` address embedding a
non-public IPv4 — JDK auto-folds an IPv4-mapped IPv6 literal into an `Inet4Address`, so the plain
IPv4 checks cover that case too, pinned by `OutboundGuardTest`) as defense in depth. The PRIMARY
boundary is the host allow-list (`isAllowedJiraHost`): exactly `api.atlassian.com`, a genuine
`*.atlassian.net` tenant per the SAME shape/reserved-label check `ingest/DataSource.kt` enforces at
create/update time (one source of truth), or — development mode ONLY — the configured
`jira.stubBaseUrl` host. `GuardedDns` (an `okhttp3.Dns`) re-checks the allow-list and re-resolves +
re-checks addresses on every NEW connection (not just once per request, since the Jira `HttpClient`
is long-lived and shared across every connection — pooled connections REUSE an already-checked
address rather than re-resolving on every call); a `skipAddressCheck` predicate exempts ONLY the
already-allow-listed stub host from the address-range check, since the dev/compose/in-JVM stub is,
by construction, a loopback or docker-network-private address — the allow-list still gates it
unconditionally, this only skips the address-SHAPE check for that one host. The guarded
`OkHttpClient` (`guardedOkHttpClient`) sets `Proxy.NO_PROXY`, `followRedirects(false)` (both at the
OkHttp engine level AND Ktor's own client-side `HttpRedirect` plugin — `HttpClient { followRedirects
= false }` in `jira/Jira.kt` — checking-then-following would defeat the address check either way),
`retryOnConnectionFailure(false)`, no cookies, no proxy authenticator. `jira/JiraHttp.kt` layers a
bounded response read (Covenant's `ToadieGraphqlClient` shape, capped at `jira.maxResponseBytes`)
and exponential backoff with jitter (`JiraBackoff`) on top. Every guard rejection is audited as
`outbound.blocked` (scheme + host ONLY — never the full URL, which may embed a token).
`jira.stubBaseUrl` is fail-closed: production startup refuses a non-blank value (`jira/Jira.kt`,
pinned by `OutboundGuardTest`'s boot test) — the same shape as the crypto-key/mail-transport
checks. A Jira Cloud API host is always public, so this guard is a defense-in-depth backstop; the
primary trust boundary is the scoped, read-only API token (see "Encryption at rest" below, whose
first consumer that token is). See `.claude/docs/jira-integration.md` for the client/auth/rate-limit
shape built on top of this guard.

What pins the transport (beyond the allow-list/address tests above): `OutboundTransportTest` runs against a LOCAL
socket server only. The response/connection release in `jira/JiraHttp.kt` (`prepareRequest {}.execute {}`) is
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
  `RuntimeDefault`, read-only root filesystem (only `/tmp`), no privilege escalation, `drop: [ALL]`.
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

**Log hygiene.** `infra/db/Flyway.kt`'s startup log line renders the operator-supplied JDBC URL
through `jdbcUrlForLogging()` — `host:port/db` only, no userinfo or query string — so a
`jdbc:postgresql://host/db?user=...&password=...` form never lands a credential in the log
(`.claude/docs/observability.md`, "never log secrets"); covered by `FlywayLogTest`. `POST
/api/v1/logout`'s malformed-body debug line (`auth/AuthRoutes.kt`) logs only the parse failure's
exception CLASS NAME, never the throwable — the cause chain can embed a body excerpt (kotlinx's
decode-error message), the same `errorType`-only rule as `login.mfa_send_failed`/
`password_reset.send_failed`; covered by `LogoutTest`.

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of
the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null.
`mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including
generated passwords**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to
start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host
refuses startup in any mode), or `disabled` (the Docker image default via
`ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The
compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8028` — 8025/8026/8027 belong
to sibling stacks); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from
`flow-secrets`. Consumers: self-service password reset and email MFA. The recipient-language
content layer (`LocalizedText`/`passwordEmail`) lives beside the transports. Tests:
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
every response instead carries `jira.hasApiToken`. Tests: `FieldCipherTest` (roundtrip, fresh
nonces, legacy passthrough, tamper, wrong key, rotation, malformed keys), `CryptoBootTest`,
`EncryptedAtRestBootTest` (seeds a connection, boots again with a rotated key, the token still
decrypts under the new key alone).

**Trusted PostgreSQL extensions.** `btree_gist` (v0.3.0 M1 commit 3, `V15__create_metrics_config.sql`
— backs `metrics.team_membership`'s overlap-exclusion constraint) joins `unaccent` (V4) as a
contrib extension trusted since PG13: `CREATE EXTENSION IF NOT EXISTS` needs only `CREATE` on the
database, no superuser — see `.claude/docs/persistence.md`'s "The `metrics` schema — configuration
(V15)".

### Not yet ported

Nothing remains on the security list beyond the forward SSRF guidance above; new subsystems
arrive with their own section here.
