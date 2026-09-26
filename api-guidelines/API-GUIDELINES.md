# HTTP API Guidelines

The authoritative standard for this project's JSON HTTP APIs — and a portable one: everything
except the [known-gaps register](#appendix-known-gaps-register) is stack-agnostic. It merges a
general API rulebook with the conventions the codebase already implements (bespoke JSON DTOs,
RFC 7807 errors, offset pagination, URL versioning, `PUT` full-document replace — each chosen
deliberately over the JSON:API alternatives; see `README.md` for the decision record). It
covers **document shape**, **resources & URLs**, **versioning & compatibility**, **collections**
(pagination, sorting, filtering), **naming**, **data formats**, **success codes**, **errors**,
**authentication & authorization**, **caching & concurrency**, **rate limiting**,
**idempotency**, **input & transport security**, **HTTP protocol**, **machine-readable
SLA/legal terms**, **OpenAPI as the contract**, and **spec ↔ implementation conformance**.

These rules are written to be **validated**, two ways:

- **Mechanically** — the machine-checkable subset is encoded in
  [`api-guidelines.spectral.yaml`](./api-guidelines.spectral.yaml), a
  [Spectral](https://stoplight.io/open-source/spectral) ruleset you run against your
  OpenAPI document.
- **By review (LLM or human)** — the semantic and operational rules a linter can't see are
  phrased as unambiguous checks. See the [LLM review checklist](#llm-review-checklist).

## How to read a rule

Each rule has a **stable ID** (cite it, e.g. *"violates API-LIST-002"*), a normative
**MUST / SHOULD** statement, a one-line **Check**, and where useful a **Why**. Tags:

| Tag | Meaning |
|---|---|
| `[spectral]` | Fully checkable by the bundled Spectral ruleset. |
| `[llm/manual]` | Requires review — semantics, runtime behavior, or transport a spec linter can't see. |
| `[both]` | Structure is linted; some part still needs review. |

Keywords **MUST / MUST NOT / SHOULD / SHOULD NOT / MAY** follow
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

> **Known gaps.** A handful of rules prescribe operational behavior the reference codebase
> does not implement yet (correlation-id echo, `Retry-After`, `ETag`, `Idempotency-Key`,
> HTTP/2, SLA metadata). They are real rules — new work should honor them — but their current
> non-conformance is **accepted and registered**, not drift. See the
> [known-gaps register](#appendix-known-gaps-register); reviewers report those items as
> "registered gap", not as findings.

---

## API-STRUCT — Document structure

Bespoke, flat JSON documents — deliberately **not** JSON:API (no `data`/`attributes`
nesting, no `application/vnd.api+json`).

### API-STRUCT-001 — Media types `[both]`
**MUST** serve request and success-response bodies as `application/json`, and every error
body (`4xx`/`5xx`) as RFC 7807 `application/problem+json` (see API-ERR-001). **MUST NOT**
introduce other JSON media types or media-type parameters.
**Check (spectral):** every request body and `2xx` response body declares `application/json`;
every `4xx`/`5xx` body declares `application/problem+json`.

### API-STRUCT-002 — Top-level shape `[both]`
**MUST** make every body a single JSON object — never a bare top-level array (arrays are
unwrappable: you can't add fields later without breaking clients). Lists use the envelope of
API-STRUCT-004.
**Check (spectral):** no response schema has top-level `type: array`.

### API-STRUCT-003 — Flat DTOs, references by id `[llm/manual]`
**MUST** model each resource as a flat DTO: domain fields at the top level in `camelCase`
(API-NAME-001), references to other resources as `<name>Id` fields (e.g. `providerId`,
`managerId`), with denormalized display fields (e.g. `managerName`) added only where a UI
needs them and documented as read-only. Related resources are fetched via their own
endpoints; **MUST NOT** embed full related objects inside a resource response. Compound
fetching (`include`-style) MAY be introduced later per-endpoint, but is not part of this
standard today.

A documented exception is a small, read-only related-resource summary (for example a team's
`name` alongside a `teamId`) where a UI needs the id and label together. These are projections,
not writable embedded resources; mutation inputs still refer to resources by id.
**Check:** resource schemas are flat or use these documented summaries; cross-references are `*Id` scalars.

### API-STRUCT-004 — List envelope `[both]`
**MUST** return every collection as the envelope
```json
{ "items": [ ... ], "page": 1, "pageSize": 20, "total": 137 }
```
— one envelope schema per resource (`UserPage`, `TeamPage`, …) wrapping the resource's
`*Response[]`, kept next to the resource's other schemas. **MUST NOT** return a bare array
or an ad-hoc wrapper. Unpaged endpoints returning an intrinsically tiny set MAY use a plain
`{ "items": [ ... ] }` wrapper, documented as unpaged.
**Check (spectral):** the `200` is not a top-level array. **Check (review):** the envelope
members and per-resource `*Page` schema exist.

---

## API-RES — Resources & URLs

### API-RES-001 — Resource-based paths under the version prefix `[both]`
**MUST** name paths after resources, all under the version prefix (API-VER-001):
a collection is `/api/v1/plural`, a single item is `/api/v1/plural/{id}`.
**Check (spectral):** every public API path starts `/api/v<major>/`.

### API-RES-002 — Collections are plural, kebab-case `[spectral]`
**MUST** name collections as plural nouns; multi-word names use `kebab-case`
(`/one-on-ones`). **MUST NOT** use `snake_case` or `camelCase` in path segments.
**Check:** each non-parameter path segment matches `^[a-z0-9]+(-[a-z0-9]+)*$`.

### API-RES-003 — Nouns in paths, verbs are HTTP methods `[both]`
**MUST** express operations through the HTTP method, not the URL. **MUST NOT** put CRUD verbs
in a path (`GET /users`, never `/getUsers`). Actions that don't fit CRUD (state transitions,
commands) **SHOULD** be a `POST` to a verb sub-path of the instance
(`POST /feedbacks/{id}/send`).
**Check:** no path segment starts with `get`/`list`/`create`/`fetch`/`retrieve`.

### API-RES-004 — Method semantics; updates are PUT full replaces `[both]`
**MUST** honor method contracts: `GET` is safe (no side effects), idempotent, and carries no
request body; updates use **`PUT` as a full-document replace** (the payload is the complete
new state; omitted optional members mean "unset", not "keep"); `PUT` and `DELETE` are
idempotent; `POST` is not. **MUST NOT** mutate on `GET`. `PATCH` (partial update) is not
used; introduce it only deliberately, per-resource, never alongside `PUT` for the same
resource.

A documented exception applies to a write-only secret field on a resource that stores a
credential encrypted at rest (for example a future integration's API token): omission preserves
the existing encrypted value, because a read cannot supply it for a replacement. An explicit
non-empty string replaces it. Other editable fields retain full-replacement semantics.
**Check (spectral):** `GET`/`DELETE` declare no `requestBody`.

### API-RES-005 — Path ids are constrained `[spectral]`
**MUST** constrain path-id parameters (`type: integer` + `minimum`, or a `pattern`/`format`
for string ids) so garbage ids are rejected at the edge (→ `400`), not deep in the stack.
**Check:** every `in: path` id parameter constrains its value (`minimum`, `pattern`, `enum`,
or `format`).

### API-RES-006 — Shallow, parent-anchored nesting `[both]`
**SHOULD** nest a sub-resource under its parent **instance**
(`/teams/{id}/members/{memberId}`) and keep nesting shallow (parent instance + child).
Deeper relationships are reached through the child resource's own endpoints, not more
`/{id}` segments.
**Check (spectral):** no path carries three or more `{param}` segments.

### API-RES-007 — Delete is idempotent in effect `[llm/manual]`
**MUST** make `DELETE` (and any soft-delete flip) safe to retry: deleting a missing **or
already-deleted** resource answers `404`, a successful delete `204`; repeating the call
converges on `404` with no further state change. With soft delete, every read/list/lookup
**MUST** filter to active rows, and mutations **MUST** be guarded to active rows so a
deleted resource behaves exactly like a missing one.
**Check:** double-DELETE yields `204` then `404`; soft-deleted rows are invisible to every
read and unmodifiable by every write.

---

## API-VER — Versioning & compatibility

### API-VER-001 — Major version in the URL path `[both]`
**MUST** carry the API's **major** version as the leading path segment pair `/api/v<major>/`
(currently `/api/v1/`) on every route. **MUST NOT** version via query parameters or
media-type parameters. Minor, backward-compatible evolution happens **within** the prefix
(API-VER-002); a new prefix (`/api/v2/`) appears only with a breaking change.
**Check (spectral):** every path matches `^/api/v\d+/`. **Check (review):** in-prefix changes
are additive only.
**Why:** visible in every URL, log line, and curl; trivially routable; one version + one
first-party client makes header negotiation pure overhead.

### API-VER-002 — Evolve additively; break only with a new major `[llm/manual]`
**MUST** evolve a released version **additively**; any breaking change goes in a new major.

- **Backward-compatible:** new endpoint; new *optional* request field/param; new response
  field; new enum value on a read-only field; relaxed validation.
- **Breaking (new major):** removing/renaming a field, endpoint, or param; making an optional
  request field required; narrowing a type or tightening validation; changing meaning, units,
  or a status code; new enum value on a field the client sends back.

**Check:** a released version's diff is backward-compatible only.

### API-VER-003 — Tolerant reader `[llm/manual]`
**SHOULD** design consumers to **ignore unknown fields** in responses; **MUST** document one
uniform policy for unknown request fields/params (ignore vs. reject) and apply it everywhere —
for query parameters that policy is settled in API-LIST-004 (unknown names ignored, repeated
scalars rejected); unknown request-BODY members are rejected by strict deserialization.
**Check:** unknown response members are tolerated; the request-side policy is documented.

### API-VER-004 — Deprecate before removal `[both]`
**SHOULD NOT** remove/break without a deprecation window. Deprecated operations/fields
**SHOULD** be marked `deprecated: true` (with a migration note), signalled with `Deprecation`
and, when known, `Sunset` headers, and keep supporting the previous major (N-1).
**Check (spectral):** anything `deprecated: true` carries a `description` with the migration
path.

---

## API-LIST — Collections (fetching)

Every list endpoint follows all of these; the OpenAPI spec documents each endpoint's
whitelists (API-NAME-004).

### API-LIST-001 — Envelope totals are consistent `[llm/manual]`
**MUST** return the API-STRUCT-004 envelope where `total` is the row count **after filters
but before pagination**, and **MUST** compute the count and the page rows within the same
transaction (or equivalent consistent snapshot) so they never disagree.
**Check:** `total` reflects the filtered set; count + rows share a transaction.

### API-LIST-002 — Offset pagination; cursor is a per-endpoint opt-in `[both]`
**MUST** paginate with `page` (1-based, default `1`, must be ≥ 1) and `pageSize` (default
`20`, max `100`); out-of-range values → `400` + problem detail. Cursor pagination is **not**
the default: adopt it only per-endpoint when stable ordering under heavy concurrent writes is
actually needed, and document the cursor format in that endpoint's OpenAPI definition.
**Check (spectral):** `page`/`pageSize` appear only as `$ref`s to the shared parameters
(API-NAME-003). **Check (review):** defaults, bounds, and `400` behavior.

### API-LIST-003 — Sorting via `sort`, `-` for descending `[both]`
**MUST** sort through a single `sort` parameter: `field` ascending, `-field` descending,
comma-separated multi-field with leftmost winning (`sort=-lastModified,id`). Each endpoint
declares an explicit whitelist of sortable fields; an unknown field → `400`. **MUST** append
`id` ascending as a deterministic tiebreaker so paging is stable, and **MUST** document the
default sort (if the client sends none, `id` ascending unless stated otherwise).
**Check (spectral):** `sort` appears only as a `$ref` to the shared parameter. **Check
(review):** whitelist enforced; tiebreaker appended; default documented.

### API-LIST-004 — Filtering: whitelisted equality + a tiny operator surface `[both]`
**MUST** filter by whitelisted equality on the field's own name (`?status=DRAFT&providerId=42`);
an unknown value in a whitelisted field → `400`. An unknown query parameter **name** is
**ignored** — deliberate leniency (clients may carry extra params; new server params must not
break old clients); "unknown fields → 400" governs values of recognized filter fields, not
the parameter namespace (clarified 2026-08-22, MT-003). Repeating a key is **reserved** to
mean `IN` (`?status=DRAFT&status=SENT`) — implement per-endpoint when a UI needs it, and
document it; until an endpoint does, a **repeated scalar key MUST be a `400`** (never silent
first-value-wins — enforced centrally in the shared param helpers since v2.35.0).
Range/operator filters use bracket suffixes, only where an endpoint needs them:
`field[gte]`, `field[gt]`, `field[lte]`, `field[lt]` for ordered types. **MUST NOT** add
`[like]`, `[ne]`, or `[in]` (use repetition for `IN`) — keep the operator surface tiny.
Booleans are strict `true`/`false`; enums use their string name; malformed values → `400`.
**Check (spectral):** query params match the camelCase-plus-bracket-operator grammar
(API-NAME-002). **Check (review):** whitelist + strict value parsing + `400`s.

### API-LIST-005 — Free-text search via `q`; per-column substring by exception `[llm/manual]`
**SHOULD** expose free-text search as a single `q` param; the endpoint decides which columns
`q` searches and documents them. Per-column, case-insensitive substring matching on the
column's own name (e.g. `?name=ali`) is allowed **only** when a UI genuinely needs per-column
matching `q` cannot express — reach for `q` first, promote to per-column only when required,
and document each occurrence.
**Check:** every substring/search param is documented with its searched columns.

---

## API-NAME — Member & parameter naming

### API-NAME-001 — camelCase members, consistently `[llm/manual]`
**MUST** use **camelCase** for every JSON member name (request and response), across the
whole API. No `snake_case`, no mixed conventions.
**Check:** every schema property uses camelCase.

### API-NAME-002 — camelCase query params, bracket suffixes only for operators `[spectral]`
**MUST** name query parameters in `camelCase` (`pageSize`, not `page_size`), with the only
bracket use being the range-operator suffixes of API-LIST-004 (`lastModified[gte]`).
**MUST NOT** use JSON:API-style family params (`page[size]`, `filter[x]`, `fields[t]`).
**Check:** every query parameter `name` matches
`^[a-z][a-zA-Z0-9]*(\[(gte|gt|lte|lt)\])?$`.

### API-NAME-003 — Reusable components `[both]`
**MUST** define the shared query parameters (`Page`, `PageSize`, `Sort`, and `Q` once a `q`
endpoint exists) once under `#/components/parameters` and `$ref` them from each list path —
never re-inline them. Shared error responses and the problem schema likewise live once under
`#/components` (see API-DOC-004).
**Check (spectral):** no inline path parameter is named `page`/`pageSize`/`sort`. **Check
(review):** error/problem schemas are shared.

### API-NAME-004 — Document sortable/filterable fields `[llm/manual]`
**MUST** document, per list endpoint, its sortable fields, filterable fields, and default
sort in the parameter/endpoint descriptions in the OpenAPI document.
**Check:** the description for each list path names its allowed sort/filter fields.

---

## API-DATA — Data-format conventions

*(Review-only — value-level conventions a spec linter can't reliably enforce.)*

### API-DATA-001 — Instants are epoch millis; dates are ISO strings `[llm/manual]`
**MUST** represent instants (timestamps) as **epoch-millisecond integers** (UTC by
definition) and date-only values as ISO `YYYY-MM-DD` **strings** (always zero-padded —
lexicographic order must equal chronological order). **MUST NOT** mix representations for
the same kind of value or use locale-dependent formats.
**Check:** every timestamp field is an epoch-millis number; every date-only field is a padded
ISO string.

### API-DATA-002 — Exact numerics as strings `[llm/manual]`
**SHOULD** carry monetary and other exact-decimal values as **strings** (never binary floats)
with an explicit currency/unit alongside, to avoid precision loss.
**Check:** money/decimals are strings with a currency/unit field.

### API-DATA-003 — Enums are named strings `[llm/manual]`
**MUST** represent enumerated values as documented, named strings (not magic numbers), and
declare the allowed set in the schema (`enum`).
**Check:** enumerated fields are string `enum`s, documented.

---

## API-OK — Success status codes

### API-OK-001 — Create → `201` + `Location` `[both]`
**MUST** answer a successful creation with `201 Created`, a `Location` header to the new
resource, and the created resource (or a creation report) in the body.
**Check (spectral):** a `201` response declares a `Location` header.

### API-OK-002 — Reads → `200` `[llm/manual]`
**MUST** answer a successful read (single or collection) with `200 OK` and a JSON body.
**Check:** `GET` success is `200` with a body.

### API-OK-003 — Updates → `200` or `204`, consistently `[llm/manual]`
**MUST** answer a successful update (`PUT`) with `200` (returning the updated resource) or
`204 No Content`, applying one convention consistently across the API (this codebase uses
`204` for ordinary replacements; the client re-fetches when it needs the new state). A future
endpoint whose PUT usefully returns computed results alongside the saved state (e.g. a document
save that also reports validation findings) MAY return `200` instead — document the exception at
the operation, not silently.
**Check:** update responses follow the documented convention.

### API-OK-004 — No-body operations → `204` `[both]`
**MUST** answer a successful operation that returns no document with `204 No Content`, and
the `204` response **MUST NOT** carry a body.
**Check (spectral):** every `204` response declares no `content`.

### API-OK-005 — Deferred work → `202` `[llm/manual]`
**SHOULD** answer accepted-but-not-completed work with `202 Accepted` (e.g. a request whose
processing continues after the response for latency-uniformity reasons).
**Check:** async operations return `202`, not `200`/`201`.

---

## API-ERR — Errors

### API-ERR-001 — RFC 7807 problem details `[both]`
**MUST** return every error body as [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807)
`application/problem+json` with the members `type`, `title`, `status`, `detail`, `instance`
(one shared `ProblemDetail` schema). Emit them through a single central helper so
content-type and shape can never drift per-route. **MUST NOT** return bare strings, HTML, or
ad-hoc error JSON.
**Check (spectral):** every `4xx`/`5xx` declares `application/problem+json` with a
`title`+`status` schema.

### API-ERR-002 — Canonical status-code mapping `[llm/manual]`
**MUST** map failures consistently:

| Condition | Status |
|---|---|
| Malformed / invalid request (incl. oversized payloads, invalid stored-text such as NUL bytes) | `400` |
| Missing / invalid authentication | `401` |
| Authenticated but not permitted | `403` |
| Resource does not exist (or is soft-deleted — API-RES-007) | `404` |
| Known path, unsupported method (problem body via the central status handler; no `Allow` header — Ktor does not surface the allowed-method set, accepted) | `405` |
| Unacceptable / conflicting media type | `406` / `415` |
| State-machine conflict, uniqueness violation (DB unique-constraint errors **MUST** be caught and mapped, never surface as `500`) | `409` |
| Failed precondition (`If-Match`) | `412` |
| Semantically invalid (well-formed) entity | `422` |
| Rate limit / throttle / lockout exceeded | `429` |
| Unexpected server error (logged) | `500` |
| Feature disabled in this deployment | `503` |

**Check:** declared error responses use these codes for these meanings.

### API-ERR-003 — Human-readable, actionable, leak-free `[llm/manual]`
**MUST** give each error a human-readable `title` and a `detail` specific enough for the
client to act on (which field, which constraint). **MUST NOT** leak secrets, stack traces, or
internals — and **MUST NOT** let error *behavior* leak information either (e.g. an
account-enumeration oracle from differing login errors; see API-ERR-006).
**Check:** validation errors identify the offending field; error text/timing reveals nothing
sensitive.

### API-ERR-004 — Correlation id on every response `[both]`
**MUST** echo a correlation/request id on **every** response via `X-Request-Id`, generating
one when the client didn't send it, and **SHOULD** surface it in problem bodies so logs and
reports can be tied to a call. *(Registered gap: the reference implementation reads the
header but does not yet echo/generate — see the appendix.)*
**Check (spectral, hint):** responses declare an `X-Request-Id` header. **Check (review):**
it is echoed/generated at runtime.

### API-ERR-005 — Declare & order the error responses `[both]`
**MUST** declare, on every operation: `500`; `401` if it requires auth; `400` if it takes
input. A bad input **MUST NOT** surface as a `500` (it is a `400`/`422`), and authorization
**MUST** be evaluated **before** payload validation (`403` outranks `400` — a non-privileged
caller learns nothing about payload rules). Validation depth is owned by **API-SEC-003**.
**Check (spectral):** each operation lists `500`; body-taking ops list `400`. **Check
(review):** `401` on secured ops; guard-then-validate ordering; no `500` from bad input.

### API-ERR-006 — Deliberate existence-disclosure policy `[llm/manual]`
**MUST** choose per resource between guard-before-read (uniform `403`, hides existence) and
read-before-guard (`404` vs `403` — reveals existence, never content), and apply the choice
consciously and consistently per resource. The same discipline applies to unauthenticated
flows: account existence **MUST NOT** be observable from responses, timing, or throttling
differences.
**Check:** each resource follows one idiom intentionally; enumeration probes learn nothing.

### API-ERR-007 — Conflicts point at the conflicting resource `[llm/manual]`
**SHOULD**, when a `409` is caused by an existing record (duplicate, open conflict), carry
that record's URI in the problem body's `instance` member so clients can link straight to it.
*(Registered gap: populated where the service knows the row today — the feedback duplicate
`409` and the days-off overlap `409`; the generic unique-violation handler cannot.)*
**Check:** duplicate-style `409`s populate `instance` with the existing resource's URI.

---

## API-AUTH — Authentication & authorization

### API-AUTH-001 — Declare authentication `[both]`
**MUST** declare authentication via OpenAPI `securitySchemes`, and every non-public operation
**MUST** carry a `security` requirement (globally or per-operation). Public endpoints are the
documented exception.
**Check (spectral):** `components.securitySchemes` is defined and a `security` requirement
applies (root or per-operation).

### API-AUTH-002 — Standard scheme, credentials in headers `[both]`
**MUST** use a standard scheme (HTTP `bearer` / OAuth2). Credentials and tokens travel in the
`Authorization` header — **MUST NOT** appear in the URL path or query string (they leak into
logs, history, and referrers).
**Check (spectral):** no `apiKey` security scheme uses `in: query`. **Check (review):** the
scheme is bearer/OAuth2.

### API-AUTH-003 — 401 vs 403 `[llm/manual]`
**MUST** return `401` when authentication is missing/invalid and `403` when the caller is
authenticated but not permitted (ties API-ERR-002 and the disclosure policy API-ERR-006).
Short-lived access tokens with refresh exchange **SHOULD** be the session model; a refresh
token **MUST NOT** authenticate an API call.
**Check:** unauthenticated → `401`; authenticated-but-forbidden → `403`; token types are
enforced.

### API-AUTH-004 — Least-privilege guards, close to the route `[llm/manual]`
**MUST** enforce per-resource authorization with explicit guards at the top of each route
handler (default-deny), and **SHOULD** document the role/permission model per operation.
Admin bypasses are deliberate, documented exceptions — never the default.
**Check:** every handler guards before acting; the permission model is documented and minimal.

---

## API-CACHE — Caching & concurrency

### API-CACHE-001 — Validators on cacheable reads `[both]`
**SHOULD** send an `ETag` (or `Last-Modified`) on cacheable `GET` responses and honor
`If-None-Match`/`If-Modified-Since` with `304 Not Modified`. *(Registered gap.)*
**Check (spectral, hint):** `GET` `200`s declare an `ETag` header. **Check (review):**
conditional requests yield `304`.

### API-CACHE-002 — Explicit `Cache-Control` `[llm/manual]`
**SHOULD** set a deliberate `Cache-Control` on every response class (e.g. `no-store` for
sensitive/authed data, `max-age` for static assets) rather than relying on defaults.
*(Registered gap: only CSS is covered today.)*
**Check:** responses carry a deliberate `Cache-Control`.

### API-CACHE-003 — Optimistic concurrency on writes `[llm/manual]`
**SHOULD** protect lost-update-prone writes with conditional requests: accept `If-Match`
against the resource's `ETag` and return `412 Precondition Failed` on mismatch. Domain-level
guards (e.g. "only the latest document is editable" → `409`) are an acceptable alternative
where they fit the model better — document which one each write uses. *(Registered gap: no
`If-Match`/`412` anywhere; the per-write inventory lives under the known-gaps register.)*
**Check:** concurrency-sensitive `PUT`/`DELETE` have a documented lost-update defense.

### API-CACHE-004 — Push for hot data `[llm/manual]`
**MAY** offer webhooks or server-sent events for frequently-changing data instead of forcing
clients to poll; when offered, the event catalog and delivery/retry semantics **MUST** be
documented. Polling with a modest interval (as the SPA does for notifications/alerts) is an
acceptable default at this scale.
**Check:** a documented push channel exists where polling would be wasteful.

---

## API-RATE — Rate limiting

### API-RATE-001 — Signal limits on `429` `[both]`
**MUST** answer a throttled request with `429 Too Many Requests` and a problem body;
**SHOULD** carry a `Retry-After` header and expose the budget via `RateLimit-Limit`,
`RateLimit-Remaining`, `RateLimit-Reset`. *(Registered gap: headers not yet sent.)*
Per-account lockouts **MUST** be indistinguishable for existing and non-existing accounts
(API-ERR-006).
**Check (spectral):** a declared `429` declares a `Retry-After` header. **Check (review):**
header values at runtime; lockout uniformity.

### API-RATE-002 — Document the limits `[llm/manual]`
**SHOULD** document the rate limits (scope, window, quota) — ideally in the machine-readable
SLA (API-META-001).
**Check:** limits are documented and discoverable.

---

## API-IDEM — Idempotency

### API-IDEM-001 — Idempotency keys on unsafe writes `[both]`
**SHOULD** accept an `Idempotency-Key` header on non-idempotent operations (`POST` that
creates; any retryable unsafe operation): replaying the **same key with the same request**
returns the original response; reusing a key with a **different** payload → `422`; the
retention window is documented. *(Registered gap: not implemented; domain-level no-duplicate
invariants — create → `409` while an open duplicate exists — currently cover the practical
double-submit cases.)*
**Check (spectral, hint):** create `POST`s declare an `Idempotency-Key` parameter. **Check
(review):** replay semantics, or a documented domain-level equivalent.

### API-IDEM-002 — Safe/idempotent methods need no key `[llm/manual]`
`GET`/`HEAD` (safe) and `PUT`/`DELETE` (idempotent by contract, API-RES-004/007) **MUST** be
naturally repeatable without a key.
**Check:** retrying `PUT`/`DELETE` converges to the same state.

---

## API-SEC — Input & transport security

*(Review-only — a spec linter cannot validate runtime/transport behavior.)*

### API-SEC-001 — HTTPS everywhere `[llm/manual]`
**MUST** serve production traffic only over TLS (**≥ 1.2, prefer 1.3**), send
`Strict-Transport-Security`, redirect HTTP→HTTPS, and manage certificates through a managed
PKI (TLS MAY terminate at a proxy/ingress — then forwarded-header handling must be explicit
and spoof-proof). Plain HTTP **MUST NOT** carry credentials or data outside local dev.
**Check:** production is HTTPS-only with HSTS; forwarded headers only honored behind a
declared proxy.

### API-SEC-002 — Injection-immune by construction `[llm/manual]`
**MUST** build all data-store access with parameterized queries / bound statements (never
string-concatenated SQL), and **MUST** neutralize/encode stored user content so downstream
consumers are not exposed to XSS. User input is untrusted everywhere — including values that
merely pass through (DB error payloads, NUL bytes → `400`, API-ERR-002).
**Check:** no dynamically-built query strings; stored content is encoded on output.

### API-SEC-003 — Comprehensive input validation, after authz `[llm/manual]`
**MUST** validate every input (type, length, range, format, allowed values) at the boundary —
feature-local validators in the route layer, run **after** the authorization guard
(API-ERR-005) — and reject violations with a clear `400`/`422` before any persistence, so
bad input never dies in the database as a `500`.
**Check:** oversized/blank/malformed inputs are rejected with a field-level message; guards
run first.

---

## API-HTTP — Protocol

### API-HTTP-001 — HTTP/2 baseline, HTTP/3 opt-in `[llm/manual]`
**SHOULD** support **HTTP/2** at the public edge (typically the TLS-terminating
proxy/ingress; the app server behind it may speak HTTP/1.1); **MAY** offer HTTP/3 (QUIC) via
`Alt-Svc`. *(Registered gap: no edge deployment configures this yet.)*
**Check:** the public endpoint negotiates HTTP/2.

---

## API-META — Machine-readable SLA & legal terms

### API-META-001 — Machine-readable SLA `[both]`
**SHOULD** publish the service-level commitments (availability target, rate limits, support
window) in machine-readable form as OpenAPI vendor extensions (a top-level `x-sla` object)
alongside human-readable Markdown. *(Registered gap.)*
**Check (spectral):** the document declares an `x-sla` extension.

### API-META-002 — Legal terms linked `[both]`
**SHOULD** reference the terms of service / legal terms from the spec
(`info.termsOfService`). *(Registered gap.)*
**Check (spectral):** `info.termsOfService` is set.

---

## API-DOC — OpenAPI as the contract

### API-DOC-001 — Spec is the single source of truth `[llm/manual]`
**MUST** maintain a hand-authored OpenAPI document as the authoritative contract and change
it **in the same change** as any route change — then regenerate client types from it and
commit them. No endpoint/param/status may exist in code but not the spec, or vice versa
(API-CONF-001 enforces this at test time).
**Check:** spec diff accompanies every route diff; generated types are in sync.

### API-DOC-002 — Keep it 3.0-compatible `[spectral]`
**MUST** stay OpenAPI 3.0-compatible while the toolchain requires it (`nullable: true`,
never `type: [..., "null"]`). Note the deliberate split: the committed document is
LABELED `openapi: 3.1.0` (openapi-typescript wants it) while its BODY stays 3.0-shaped —
the conformance harness relabels it 3.0.3 in memory for swagger-request-validator
(`OpenApiConformance.kt`) and `OpenApiSpecTest` pins that no 3.1-only construct creeps in.
**Check:** no schema uses a `type` array containing `"null"`.

### API-DOC-003 — Unique operationIds `[spectral]`
**MUST** give every operation a unique `operationId`.
**Check:** no `operationId` is repeated.

### API-DOC-004 — Shared error responses, inline only for specifics `[llm/manual]`
**SHOULD** define the generic error responses (`Unauthorized`, `BadRequest`, `NotFound`,
`Conflict`, `TooManyRequests`, `InternalServerError`, …) once under `#/components/responses`
and `$ref` them; declare an error inline **only** to give it a case-specific `description`
(the body is still the shared `ProblemDetail` schema — API-ERR-001 lints that either way).
**Check:** generic errors are `$ref`s; inline declarations carry a case-specific description.

---

## API-CONF — Spec ↔ implementation conformance

Conformance is a property of the test/verification harness, not of a static document — so all
of these are `[llm/manual]`.

### API-CONF-001 — Automated conformance checking `[llm/manual]`
**MUST** verify real request/response traffic against the spec automatically, so drift fails
the build (undeclared endpoint/method/status, response-schema or content-type mismatch) —
e.g. by validating the test suite's HTTP interactions against the spec via a client-side
validator plugin installed in the shared test client.
**Check:** a response deviating from the spec fails CI.

### API-CONF-002 — Response-side validation mandatory; request-side may relax `[llm/manual]`
**MUST** fully validate responses; request-side validation **MAY** relax to unknown
path/method so deliberately-malformed negative tests survive.
**Check:** negative tests run; response conformance stays on.

### API-CONF-003 — Track conformance coverage `[llm/manual]`
**SHOULD** report exercised vs. declared (operation, status) pairs after each test run — a
report, not a gate.
**Check:** a coverage report is produced.

### API-CONF-004 — Spec-driven fuzzing `[llm/manual]`
**MAY** run property-based fuzzing from the spec (Schemathesis) periodically; a `5xx` count
above zero is the signal. Runs are manual/out-of-CI; findings are triaged to zero
unexplained `5xx`s.
**Check:** fuzz runs are reproducible and triaged.

---

## LLM review checklist

Feed this file plus the target OpenAPI document (or route code) to a reviewer and have it
assess the `[llm/manual]` and `[both]` rules — pass / fail / N-A, citing the rule ID. Items
on the [known-gaps register](#appendix-known-gaps-register) are reported as **"registered
gap"**, not as findings.

- **API-STRUCT-003/004** — Flat camelCase DTOs with `*Id` references; every list wrapped in
  the `{items,page,pageSize,total}` envelope with a per-resource `*Page` schema?
- **API-RES-004/007** — `PUT` is a documented full replace; `GET` never mutates; deletes
  idempotent in effect with soft-deleted rows invisible everywhere?
- **API-VER-001/002/003/004** — Everything under `/api/v1/`; in-version changes
  backward-compatible; tolerant reader; deprecations carry notice + `Deprecation`/`Sunset`?
- **API-LIST-001/002** — `total` after filters before paging, count + rows in one
  transaction; `page`/`pageSize` bounds enforced with `400`s; any cursor adoption documented
  per-endpoint?
- **API-LIST-003/004/005 / API-NAME-004** — Sort whitelist rejecting unknown fields with
  `400`; unknown values of recognized filter fields `400` (unknown parameter NAMES are
  deliberately ignored — see API-LIST-004); repeated scalar keys `400` until an endpoint
  implements documented `IN`; `id` tiebreaker; strict boolean/enum parsing; documented
  `q`/substring params?
- **API-NAME-001** — camelCase members everywhere?
- **API-DATA-001/002/003** — Epoch-millis instants + padded ISO dates; exact numerics as
  strings; enums as named, documented strings?
- **API-OK-001..005** — Correct success code per operation (`201`+`Location`, `200`,
  consistent update convention, `204` no-body, `202` deferred)?
- **API-ERR-002/003/006/007** — Right status per meaning (23505 → `409`, NUL → `400`);
  actionable leak-free errors; one intentional existence-disclosure idiom per resource;
  duplicate `409`s carrying `instance` *(registered gap for the generic handler)*?
- **API-ERR-004** — Correlation id echoed on every response? *(registered gap)*
- **API-ERR-005 / API-SEC-003** — `500`/`401`/`400` declared; authz before validation; every
  boundary input validated feature-locally?
- **API-AUTH-001..004** — Schemes declared + applied; bearer with credentials in headers
  only; `401` vs `403` + token-type enforcement; default-deny guards at the top of every
  handler?
- **API-CACHE-001..004** — ETag/304 *(registered gap)*; deliberate `Cache-Control`
  *(registered gap)*; a documented lost-update defense per concurrent write *(registered gap —
  see the register's inventory)*; push channel where polling would be wasteful?
- **API-RATE-001/002** — `429` + problem body; `Retry-After`/`RateLimit-*` *(registered
  gap)*; uniform lockout behavior; limits documented?
- **API-IDEM-001/002** — Idempotency keys or a documented domain-level equivalent
  *(registered gap)*; `PUT`/`DELETE` naturally repeatable?
- **API-SEC-001/002** — HTTPS-only + HSTS in production; spoof-proof forwarded headers;
  parameterized queries + output encoding?
- **API-HTTP-001** — HTTP/2 at the public edge? *(registered gap)*
- **API-META-001/002** — `x-sla` + `info.termsOfService`? *(registered gaps)*
- **API-DOC-001/004 / API-CONF-001..004** — Spec changed with the route change and types
  regenerated; generic errors shared; drift fails CI; response-side conformance on; coverage
  tracked; fuzzing triaged?

---

## Appendix: known-gaps register

Accepted, registered non-conformances of the reference implementation (Kotlin/Ktor). Each is
prescribed by a rule above, deliberately not implemented yet, and cheap to adopt when
prioritized. Reviewers cite these as "registered gap"; the Spectral ruleset carries them at
`hint`/`warn` severity so they never fail a lint. Remove an entry when the gap is closed.

| Rule | Gap | Adoption pointer |
|---|---|---|
| API-ERR-004 | `X-Request-Id` is read but not echoed or generated | `CallId` config in `plugins/Monitoring.kt`: add `replyToHeader(HttpHeaders.XRequestId)` + `generate { ... }`; declare the header on responses in the spec |
| API-ERR-007 | Duplicate-style `409`s identify the failing request, not the conflicting resource | All problems carry an occurrence path in `instance`; add a conflicting-resource link where the service knows the row id and API-ERR-006 permits disclosure. The generic 23505 handler cannot safely infer it |
| API-RATE-001 | No `Retry-After` / `RateLimit-*` headers on `429`s | Set `Retry-After` where the wait is known (login lockout knows its window); add headers to the shared `TooManyRequests` response |
| API-CACHE-001/002 | No `ETag`/`304`; `Cache-Control` only on CSS | Install `ConditionalHeaders`; extend the `CachingHeaders` config in `plugins/Http.kt` with deliberate per-class policies (`no-store` on API responses) |
| API-CACHE-003 | No `If-Match`/`ETag`/`412` conditional writes anywhere; unguarded full-document writes are last-write-wins (see the inventory below) | Add `ETag` + `If-Match` handling to the concurrency-sensitive `PUT`s if contention ever materializes; a `version` column + `409` is the R2DBC-friendly alternative |
| API-IDEM-001 | No `Idempotency-Key` handling | Domain no-duplicate `409`s cover double-submits today; adopt the header if external/retrying clients appear |
| API-HTTP-001 | HTTP/1.1 only (Netty defaults; no edge HTTP/2) | Configure HTTP/2 at the TLS-terminating ingress when one exists |
| API-META-001/002 | No `x-sla`, no `info.termsOfService` in the spec | Add both to `documentation.yaml` when commitments/terms exist to publish |
| API-DOC-004 | Some generic error declarations are inline duplicates rather than `$ref`s | Fold pure duplicates into `#/components/responses` opportunistically; keep case-specific descriptions inline |

### Lost-update inventory (the API-CACHE-003 row's per-write record)

Per API-CACHE-003, each concurrency-sensitive write documents its defense (current as of the
v0.1.0 foundation — extend this list as domain features land):

- **Accepted last-write-wins** — full replacements of users, feature flags, language, and teams.
  Permissions limit writers but do not prevent one authorized editor from overwriting another
  (e.g. two admins editing the same team's roster). No conditional-write (`If-Match`/`ETag`)
  mechanism exists yet; adopt one for a specific endpoint only if contention actually
  materializes there.
- **Credential-revision guard (a narrower case, not `If-Match`)** — a password change/reset
  atomically increments `users.credential_revision`; refresh and MFA acceptance compare it
  against the current row and reject a stale credential rather than silently accepting it. See
  `.claude/docs/persistence.md` "Credential revision".
- **Parent-existence guards** — a team roster mutation (`POST`/`DELETE .../members/{userId}`)
  re-checks the team and member rows are still active inside its own transaction; a
  concurrently soft-deleted parent or member is a `404`, not a silent no-op.

A future domain feature that introduces its own document-save or multi-step write (e.g. the
Jira ingestion's incremental cursors, v0.2.0) should add its own row here, following Covenant's
pattern: serialize on the owning parent's lock, revalidate the guards that changed since the read,
and reject a stale write with `409` rather than silently overwriting it.
