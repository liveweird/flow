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
`TokenBlocklistCacheTest`). Full token/refresh/logout semantics: see "Authorization model" in
`.claude/docs/authorization.md`.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in
`localStorage` under `flow.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId,
disabledFeatures and a random session identity; no password is ever persisted; everything else in
localStorage is view state). A deliberate trade-off, documented rather than hidden: any script
running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP
revocable through `/logout` or a password change/reset — so the real control is keeping foreign
script out. That control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest`: a strict CSP (`script-src 'self'`, `object-src 'none'`, `base-uri 'self'`,
`frame-ancestors 'none'`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`. Consequences: never weaken `script-src` (no CDN scripts, no
`unsafe-inline` — Vite bundles everything same-origin, fonts included), and the SPA must stay free
of `dangerouslySetInnerHTML`/`eval` sinks (currently zero). Moving tokens to httpOnly cookies would
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
`login`, `refresh`, `password-reset`, `mfa`): blank **follows the mode** — 10/min in production,
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

**Outbound HTTP calls (SSRF posture — applies from v0.2.0).** No server code makes outbound HTTP
calls yet. When the Jira Cloud connector lands, **port Toadie's `UrlFetch.kt` guard rather than
inventing a new one**: absolute `https` only, no userinfo; every resolved address checked and
refused if loopback, site-local, link-local, any-local, multicast, IPv6 unique-local `fc00::/7`,
CGNAT `100.64.0.0/10`, `192.0.0.0/24`, benchmarking `198.18.0.0/15`, or a NAT64 `64:ff9b::/96`
address embedding a non-public IPv4 (unresolvable hosts refused too); `followRedirects(NEVER)`
(checking-then-following would defeat the address check — load-bearing); bounded connect/read
timeouts and a bounded response read (never an unbounded `ofString`). Audit every guard rejection
uniformly (scheme/host only — never the full URL, which may embed tokens) and every successful
fetch the same shape, so the security log records who had the server pull from where. A Jira
Cloud API host is always public, so this guard is a defense-in-depth backstop; the primary trust
boundary is the scoped, read-only API token (see "Encryption at rest" below, whose first consumer
that token will be).

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when
`http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is
single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller
exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config
`http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders` so
rate-limit buckets key on the real client IP and the HTTPS redirect sees the real scheme; the
proxy must set (and strip client-supplied) `X-Forwarded-For`/`X-Forwarded-Proto`. Off by default
because honoring those headers from direct clients lets them spoof both.

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
`kubectl create secret generic` command in its header; the app deployment consumes it via
`secretKeyRef`).

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

**Encryption at rest** (`infra/crypto/`, Lettuce's, ported verbatim, **wired but not yet
consumed**). Sensitive columns are encrypted application-side with AES-256-GCM (`FieldCipher`: a
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
`encryptedAtRestServices()` list (empty today) — never remove a registration once one lands (a
rotation would strand that feature's rows). **Never filter or sort on an encrypted column in
SQL** — ciphertext carries no order or equality (every value has its own nonce). The v0.2.0 Jira
API token is the planned first consumer. Tests: `FieldCipherTest` (roundtrip, fresh nonces, legacy
passthrough, tamper, wrong key, rotation, malformed keys), `CryptoBootTest`.

### Not yet ported

Nothing remains on the security list beyond the forward SSRF guidance above; new subsystems
arrive with their own section here.
