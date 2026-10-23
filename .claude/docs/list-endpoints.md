### List endpoint conventions

> **Ported.** `server/src/main/kotlin/infra/paging/` (`Paging.kt`, `QueryParams.kt`, `PageResponse.kt`) is Lettuce's
> implementation, ported via Toadie; `GET /api/v1/flow-users` is the reference implementation. The conventions below are the
> contract the package enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs):

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the
  `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination,
  with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented
  per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint
  whitelist → `400`; `id` asc always appended as tiebreaker; default `id` asc documented
  [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]`
  bracket operators only where needed; strict `true`/`false` booleans; enums by string name; a repeated
  scalar key is a `400` (never silent first-value-wins; unknown parameter *names* stay ignored by deliberate
  leniency), see "Repeated keys" below [API-LIST-004].
- Free text: `q` first, per-column substring only when a UI requires it [API-LIST-005].
- Naming: camelCase params; reusable `Page`/`PageSize`/`Sort` under `#/components/parameters`
  `$ref`'d from each list path; one `*Page` envelope schema per resource; sortable/filterable
  whitelists documented per path [API-NAME-001..004].

**Implementation shape:** `ApplicationCall.parsePaging(sortable = setOf(...))` parses
`page`/`pageSize`/`sort` from the query string against the per-endpoint whitelist into a
`PageRequest` (appending the `id`-asc tiebreaker); `Query.applyPaging(req, columns)` applies
`.orderBy(...)` + `.limit(...).offset(...)` to an Exposed `Query` via the service's file-level
`SORTABLE_COLUMNS` map (the two whitelists must agree — a missing column is an `error()`, i.e. a
500-grade invariant violation). Validation failures throw `BadRequestException` → `400` +
`ProblemDetail`. The service runs `count()` + the page rows against ONE shared predicate in ONE
`suspendTransaction` and returns an `XListResult(items, total)`; the route responds
`paging.toPage(items, total)` and the DTO file declares `typealias XPageResponse = PageResponse<XResponse>`.
New list endpoints copy `flowusers/` (service `list`, routes `get<X>`, the OpenAPI `$ref`s to the shared
`Page`/`PageSize`/`Sort` parameters and a per-resource `*Page` schema) rather than re-parsing params.

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive —
use `containsNormalized` (`infra/db/Sql.kt`, rendering
`LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?))`, extension enabled in V4); never
hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse
breaks uppercase-diacritic input under a C-locale database). The SPA mirrors the rule via the
theme-level `foldedOptionsFilter` (`web/src/utils/text.ts`).

**Repeated keys (`IN`).** A scalar key repeated is a `400` (`singleValue`); a parameter documents `IN` semantics through the `repeatedValues` family (`repeatedLongs`/`repeatedStrings` in `infra/paging/QueryParams.kt`), declared in the spec as `type: array` with `style: form`, `explode: true` and `minItems`/`maxItems`, with duplicates collapsed (`distinct()`) before the bound applies. The per-endpoint ledger of every consumer (deep-dive, the `accountId` pickers, Home's `teamId`, the Costs `key`, Compare people, `topEpicId`, …) lives in `.claude/docs/reports.md` "Repeated query parameters".

**The report drill lists are the one report-shaped exception.** `GET /api/v1/reports/<report>/items` and `GET /api/v1/reports/home/items` (the items behind one bar of a
chart or one figure of the Home overview, `.claude/docs/reports.md` "Drill to items" and "Home overview") answers `{meta, bar, items[<=500], total, truncated}`, not the paged envelope: the
fixed `{items, page, pageSize, total}` cannot carry the `meta` and the server-recomputed `bar` the page prints and reconciles against,
the full fetch and assignment run in the JVM regardless, and the bound is a hard cap with a `truncated` flag, so paging buys nothing.

**Sub-collections and facets:** a record's own unbounded sub-collection is paged like any other list; the plain `{items}`
wrapper is reserved for genuinely bounded, admin-curated sets. Facet counts (`GET .../facets`) are not implemented — port Covenant's
pattern with the first filterable list that needs them.
