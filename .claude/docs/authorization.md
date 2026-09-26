### Authorization model

Layered RBAC. Implemented in the `server/src/main/kotlin/authz/` package.

- **Global roles are an additive set**: every user is implicitly a regular user (USER is the
  baseline — never transmitted), and additional roles — currently only `ADMIN` (`UserRole` in
  `users/User.kt`; a future role is just a new enum value) — only ever ADD privileges. The API
  carries them as a `roles` array (`[]` = regular user); `isAdmin()` (`authz/Guards.kt`) =
  `ADMIN ∈ roles`. Storage is a single `users.role` column with a CHECK (V1) — the **wire shape is
  already the set** (`roles` claim + `LoginResponse.roles`), so moving to a Lettuce-style
  `user_roles` join table when a second additional role arrives never breaks issued tokens or
  clients. **ADMIN is the management role**: it owns the whole user-management surface (the
  `/users` CRUD below) and may reset any user's password (`requireSelfOrAdmin`'s admin branch);
  the management surfaces attach to it (users and, with flat teams, team CRUD and membership).
- **Login** issues a **pair** of JWTs, both carrying `email`, `userId`, a `roles` array claim, and
  a `typ` claim (`"access"` / `"refresh"`), minted by `auth/Tokens.kt`
  (`JwtConfig.issueAccessToken` / `issueRefreshToken`). The short-lived **access** token
  (`jwt.accessExpiresInSeconds`, default 900) is the API bearer; the longer-lived **refresh**
  token (`jwt.refreshExpiresInSeconds`, default 3600) is exchanged at `POST /api/v1/refresh` for a
  fresh pair. `LoginResponse` (also the `/refresh` response) exposes `token`/`expiresAt`,
  `refreshToken`/`refreshExpiresAt`, `userId`, and `roles`. The `jwt {}` verifier in
  `plugins/Security.kt` additionally requires `typ == "access"` and a non-revoked `jti`, so a
  refresh token cannot authenticate an API call.
- **Session auto-extension (pure sliding).** `/api/v1/refresh` (rate-limited, no `authenticate` —
  the access token may be expired) verifies the refresh token's signature/issuer/audience/`typ`/
  non-revocation, then does **one** `userService.read(userId)` to confirm the user is still active
  (soft-delete-filtered) and pick up their current roles/email, **requires the signed
  refresh-token `credentialRevision` to equal `users.credential_revision`**. Every password
  change/reset/bootstrap rotation increments that revision atomically with the hash update, so
  even changes in the same millisecond invalidate earlier credentials. Missing or malformed
  revision claims are rejected. Superseded tokens are **not** rotated out — they stay valid until
  their own expiry (no blocklist write on refresh); only an idle session (no refresh within the
  refresh TTL) ends. Every rejection is audited (`refresh.rejected` with `reason`). Frontend:
  `web/src/api/http.ts` `authedFetch` silently refreshes (single-flighted within the current
  session) and retries once on a `401`; on a definitive refresh rejection it clears only the
  session that initiated the request. Responses from an obsolete session cannot publish
  credentials, clear a newer session, or retry an operation as the newer account. Logout clears
  local credentials immediately and revokes its captured token pair best-effort.
- **The public surface** is exactly six operations, each declaring `security: []` in the spec:
  `POST /login`, `/login/mfa`, `/refresh`, `/password-reset`, and the two probes
  `GET /api/v1/health` (liveness — the process answers) and `GET /api/v1/ready` (readiness — a
  database round trip within a short budget, else `503`; `plugins/Health.kt`). Probes disclose
  nothing but `{status: ok}`; everything else sits inside `authenticate {}` (`AnonymousAccessTest`
  sweeps it from the spec — the "401 sweep": every operation without a declared `security: []` is
  probed token-less, so a new route is covered the moment its spec entry lands).
- **Guards** in `authz/Guards.kt` are plain `suspend`-friendly function calls used at the top of
  each route handler — there is no Ktor plugin or DSL. Each route reads `call.caller()` (which
  parses the JWT claims into a `CallerPrincipal{userId, email, roles}` — missing/unknown claims
  throw `UnauthorizedException`) and then invokes the relevant guard: `isAdmin(caller)`,
  `requireAdmin(caller)`, `requireSelfOrAdmin(caller, targetUserId)`, plus `requireFeatureEnabled`
  (awaiting its first area-gating consumer); new guards join this file so the whole policy stays
  in one place.
- **Resource rules**:
  - `/api/v1/users` (list/create) and `PUT`/`DELETE /api/v1/users/{id}` → **ADMIN only** — the
    whole management surface is admin-gated (the list included; regular users keep only the self
    password change). `GET /users/{id}` → target user or ADMIN, **guard-before-read** (an
    unauthorized caller 403s uniformly whether or not the id exists). Two protections with a
    deliberate 403/409 split: **self-delete is a `ForbiddenException` (403)** — a
    permissions-shaped rule about WHO acts — while **deleting or demoting the LAST active
    administrator is a `ConflictException` (409)** — a state-shaped rule about what the system
    can afford to lose. Initial passwords are client-generated (the SPA) and stored only as
    bcrypt hashes; no response carries plaintext.
  - `/api/v1/teams` (V6, flat teams) → `GET` list and `GET /{id}` **any authenticated user**
    (teams are the ownership unit later domain features will point at: the Teams page reads
    them); `POST`, `PUT`/`DELETE /{id}` and the roster pair `POST`/`DELETE /{id}/members/{userId}`
    **ADMIN only**, with `requireAdmin` BEFORE the body decodes and the id lookup
    (guard-before-read: a non-admin probe gets a uniform 403 whether or not the id exists, and a
    non-admin's malformed body stays 403). A create's unknown/deleted member id is `400` (a
    client-supplied FK), a single add's unknown user `404`, an existing member `409`; a
    case-insensitive name clash with an active team `409` (the V6 partial index).
    `TeamService.activeTeamIdsOf(userId)` is the lookup a future ownership guard would run inside
    its own transaction. Mutations audit `team.created/updated/deleted` and
    `team.member_added/member_removed`. Tests: `TeamTest`.
  - `PUT /api/v1/users/{id}/features` → ADMIN only; **self allowed** (an admin adjusting their own
    flags is a feature, and the users routes are never gated, so a fully self-disabled admin can
    always get back); unknown/soft-deleted target → 404; unknown feature name → 400 (enum
    decode); wholesale replace (idempotent — same-set re-PUT is 204, not 409); audited
    `user.features_changed` (featuresFrom/featuresTo) only on an actual change. **Per-user feature
    flags (V5, Lettuce's model)**: `Feature { MFA }` stored as the DISABLED set
    (`user_disabled_features`, no row = enabled). **`MFA` is inverted-default and login-scoped** —
    every user starts with the MFA row present (the V5 seed + the `UserService.create`
    chokepoint), so email MFA is opt-in; it gates the LOGIN FLOW only, and no route names it in
    `requireFeatureEnabled` (which awaits Flow's first area-gating feature; run it as the FIRST
    guard when one arrives — before any read, so a disabled caller gets a uniform 403 even for a
    missing id). Mind the wholesale-replace PUT: a disabled set omitting MFA — including the
    empty "re-enable everything" array — ENABLES the second factor. The set rides both JWTs as
    the `disabledFeatures` claim (missing claim = empty — outstanding tokens survive the deploy;
    unknown value = 401) and `LoginResponse`/`UserResponse.disabledFeatures`, so a change takes
    effect at the target's next refresh (≤15 min) or login. Users list:
    `feature=<F>&featureEnabled=true|false` filter pair (must come together, 400 otherwise). SPA:
    `hasFeature()` (`api/session.ts`, localStorage `flow.auth.disabledFeatures`), the per-user
    editor `/users/:id/features` (the Users table's Features button) and the admin per-feature
    screen `/feature-flags` (state filter + bulk enable/disable over every row matching the
    current filters, behind a count-stating confirm — a client-side loop of the same per-user
    wholesale PUTs). Tests: `FeatureFlagsTest` + the GuardsTest/AuditTest cases.
  - `PUT /api/v1/users/{id}/language` → **target user or ADMIN** (`requireSelfOrAdmin`, the V1
    `language` column — Lettuce's model): the ONE synced per-user language, set at create
    (`UserCreateRequest.language`, default `"en"`), applied to the SPA at sign-in (rides
    `LoginResponse`, applied by `persistSession` on login/MFA/refresh) and used for every email
    sent to the user (read fresh at send time). The header language switcher is the self-service
    writer (fire-and-forget save on switch); the whole-user PUT deliberately never touches it.
    Unsupported code → 400 (after the guard — 403 wins); idempotent; audited
    `user.language_changed` on an actual change. Tests: `UserLanguageTest`.
  - `PUT /api/v1/users/{id}/password` → target user or ADMIN (`requireSelfOrAdmin`). New password
    must be ≥ 10 chars (`MIN_PASSWORD_LENGTH` in `users/UserRoutes.kt`) and ≤ 71 UTF-8 bytes
    (`MAX_PASSWORD_BYTES`, `auth/Passwords.kt` — the bcrypt ceiling), else `400`. A **self-change**
    (even by an admin) additionally requires a correct `currentPassword` in the body (else `403`,
    audited `password.change_denied`) — checked BEFORE the length validation, so 403 wins over
    400 (the convention everywhere); an admin resetting **another** user's password does not. A
    successful change stamps `users.password_changed_at` and atomically increments
    `users.credential_revision`, which invalidates all outstanding refresh tokens and pending MFA
    challenges (see "Session auto-extension"); already-issued access tokens keep working until
    their ≤15-min expiry (documented bounded window). Covered by `PasswordChangeTest`.
- **Existence disclosure (403 vs 404) — deliberate policy (API-ERR-006)**: two sanctioned idioms,
  and every new resource picks one consciously rather than mixing them. **Guard-before-read**
  (`GET /users/{id}`): a forbidden caller gets a uniform 403 whether or not the id exists — use it
  where existence itself is sensitive. **Read-before-guard**: missing → 404, existing-but-
  forbidden → 403 — acceptable where ids are sequential and existence is no secret. The password
  PUT effectively guards first (`requireSelfOrAdmin` needs no read), then maps a zero-row update
  to 404 — safe because the only callers past the guard are the target and admins.
- **Audit on denial**: every 403 emits `authz.denied` (method/path/byUserId/detail) from the
  `ForbiddenException` handler in `plugins/ErrorHandling.kt` — denials are part of the security
  trail, and route code gets it for free by **throwing**, never hand-rolling a 403 response. See
  "Audit trail" in `.claude/docs/observability.md`.
  - `/api/v1/data-sources` (v0.2.0, V8) → **ADMIN only, the whole surface** — unlike teams, the
    list/get reads are gated too (there is no any-authenticated read here; the connection holds a
    credential). `requireAdmin` runs BEFORE `call.receive()` on every mutation (guard-before-read,
    like teams). `jira.siteUrl` is the connection's identity (mirrors Toadie's `baseUrl` rule): a
    PUT changing it is `409`; a case-insensitive name clash with an active data source is `409`
    (the V8 partial index). `jira.apiToken` is write-only — required on create, optional on
    update (omitted keeps the current token, present rotates it, audited separately as
    `data_source.token_rotated`); no response or audit event ever carries it, only
    `jira.hasApiToken`. Delete is soft (disables the connection; the raw/normalized rows purge
    later per the v0.2.0 plan's grace-period amendment). Mutations audit
    `data_source.created`/`.updated`/`.token_rotated`/`.deleted`. Tests: `DataSourceRoutesTest`.
  - `/api/v1/data-sources/{id}/sync-jobs` (`POST`/`GET`, `GET .../sync-jobs/{jobId}`,
    `POST .../sync-jobs/{jobId}/cancel`, `ingest/SyncJobRoutes.kt`) → **ADMIN only**, `requireAdmin`
    before `call.receive()` on the enqueue mutation (guard-before-read/-body, the data-sources
    idiom). A caller-requested `PURGE` kind is `400` — only the scheduler enqueues it. Mutations
    audit `sync_job.requested`/`.cancel_requested`. Tests: `SyncJobRoutesTest`.
  - `GET /api/v1/data-sources/{id}/status` (v0.2.0 plan §9/§12 item 7, `ingest/SyncStatusRoutes.kt`)
    → **ADMIN only, read-only** — a diagnostic view over state the data-sources/sync-jobs/cursors
    surfaces above already own (connection summary, stream cursors, raw-store counts, last job per
    kind, the running job), assembled here rather than duplicated. No mutation, so no audit event of
    its own — see `.claude/docs/ingestion.md` "Sync status endpoint" for the response shape. Tests:
    `SyncStatusRoutesTest`.
- **Exceptions**: `UnauthorizedException` (→ 401), `ForbiddenException` (→ 403),
  `NotFoundException` (→ 404), `ConflictException` (→ 409), `TooManyRequestsException` (→ 429),
  and `BadGatewayException` (→ 502 — reserved for a future outbound-fetch upstream failure) live
  in `authz/Exceptions.kt` and are mapped in `plugins/ErrorHandling.kt`. **All error bodies are
  RFC 7807 `application/problem+json` (API-ERR-001/002)**
  (`ProblemDetail{type,title,status,detail,instance}`; `instance` defaults to the request path
  without query parameters) — emit them via the `ApplicationCall.respondProblem(status, detail)`
  helper (it uses an explicit `TextContent` so ContentNegotiation does not relabel the media type
  as `application/json`). `StatusPages` routes `BadRequestException`→400 (**with fixed vocabulary
  for converter failures**: a `ContentConvertException` anywhere in the cause chain
  (ContentNegotiation's malformed-JSON wrap, whose raw message carries the target FQCN) answers
  "Request body is invalid or does not match the expected schema", and the Resources decode wrap
  "Can't transform call to resource" answers "Path or query parameter is malformed"; our
  validators' own messages, cause-less, pass through), `CannotTransformContentToTypeException`→400
  when Content-Type is absent, otherwise →415 for an unsupported request media type (the abstract
  ContentTransformationException parent is deliberately not caught), the typed exceptions as above
  (**the route 404 convention**: read preambles and zero-row mutation results
  `throw NotFoundException("<Resource> not found")` — never a hand-rolled
  `respondProblem(NotFound, …)` + return), unique-violation `23505`→409 and NUL-byte `22021`→400
  (via the R2DBC/Exposed cause-chain walkers), and a catch-all `Throwable`→500 (logged) through
  that helper. A `status(TooManyRequests)` handler additionally gives the per-IP `RateLimit`
  plugin's bodiless 429 a generic problem body — **that handler rewrites any non-StatusPages
  429**, which is why caller-specific 429s (the login lockout) must go through
  `TooManyRequestsException` (handled calls are marked and skipped) — and a sibling
  `status(MethodNotAllowed)` handler gives routing's bodiless wrong-method 405 a problem body too
  (no `Allow` header — Ktor doesn't surface the allowed-method set; test with the default client,
  the conformance plugin would rightly flag the out-of-spec operation). A pre-routing intercept in
  `configureErrorHandling` additionally 400s any `/api/` path with a negative-integer segment
  (kotlinx decodes `UInt` via `toInt().toUInt()`, so `/users/-1` would WRAP to 4294967295 and flow
  into the normal lookup instead of failing as the spec's `minimum: 0` promises). The JWT
  `challenge` in `plugins/Security.kt` also calls `respondProblem` (it runs outside
  `StatusPages`). Test HTTP clients must register the `application/problem+json` content type
  (`json(contentType = ContentType.parse("application/problem+json"))`) to decode error bodies
  with `body<ProblemDetail>()` — the shared test-client defaults do.
- **Tests**: `GuardsTest` covers the guard matrix, `AnonymousAccessTest` sweeps the 401 surface
  FROM THE SPEC (plus the forged-secret and jti-less token cases), `UserRoutesTest` the management
  CRUD + protections (with `TestUsers.withSoloAdmins` isolating the last-admin 409s in the shared
  container), `PasswordChangeTest` the self-vs-admin rules, `AuditTest` the denial and mutation
  events. The shared `TestUsers.seed` helper defaults to `role = UserRole.ADMIN` so privileged
  fixtures are terse; pass `role = UserRole.USER` when you need a non-privileged caller.

### Not yet ported from Lettuce

- **The HR read-only auditor role** (audited `hr.read`/`hr.list` cross-user reads) — with it,
  Lettuce's rule that every HR-privileged read is audit-logged.
- **The management-chain rule** (`teams/ManagementChain.kt` — transitive manager rights) —
  **deliberately NOT ported**: Flow's teams are flat (membership only, no manager, no chain). Port
  only if a hierarchy of teams ever becomes a requirement.
