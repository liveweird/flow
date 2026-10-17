### List endpoint conventions

> **Ported.** `server/src/main/kotlin/infra/paging/` (`Paging.kt` + `QueryParams.kt` +
> `PageResponse.kt` — `PageRequest`, `parsePaging`, `applyPaging`, `toPage`, the strict
> `singleValue` param reader and the `optionalString/Boolean/Enum` parsers) is Lettuce's
> implementation, ported via Toadie. `GET /api/v1/users` is the reference implementation today.
> Lettuce's view-scoped helpers (`optionalIncludeIndirect`, `uintOnlyForView`,
> `optionalUInt`/`optionalLong`) were deliberately left behind; re-port them with their first
> consumer. The conventions below are the contract the package enforces.

**The generic list rules are owned by `api-guidelines/API-GUIDELINES.md`** (cite the rule IDs) —
summary:

- Offset pagination `page`/`pageSize` (1-based, default 20, max 100; out-of-range → `400`) in the
  `{items, page, pageSize, total}` envelope; `total` is computed after filters, before pagination,
  with count + page rows in the **same `suspendTransaction`**; cursor paging is a documented
  per-endpoint opt-in [API-LIST-001/002, API-STRUCT-004].
- Sorting: `sort=field`/`-field`, comma-separated multi-field, validated against a per-endpoint
  whitelist → `400`; `id` asc always appended as tiebreaker; default `id` asc documented
  [API-LIST-003].
- Filtering: whitelisted equality filters on the field's own name plus `field[gte|gt|lte|lt]`
  bracket operators only where needed; strict `true`/`false` booleans; enums by string name;
  repeated key reserved for `IN` — on every scalar key repetition stays a `400` (Lettuce's
  `singleValue` rule — never silent first-value-wins; unknown parameter *names* stay ignored by
  deliberate leniency) until an endpoint documents `IN` semantics via `repeatedValues`
  [API-LIST-004].
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
New list endpoints copy `users/` (service `list` + routes `get<X>` + the OpenAPI `$ref`s to the
shared `Page`/`PageSize`/`Sort` parameters and a per-resource `*Page` schema) rather than
re-parsing params.

**Substring filters:** every per-column substring filter must be case- AND accent-insensitive —
use `containsNormalized` (`infra/db/Sql.kt`, rendering
`LOWER(public.unaccent(col)) LIKE LOWER(public.unaccent(?))`, extension enabled in V4); never
hand-roll `lowerCase() like containsPattern(...)`, and keep unaccent-before-LOWER (the reverse
breaks uppercase-diacritic input under a C-locale database). The SPA mirrors the rule
client-side via the theme-level `foldedOptionsFilter` (`web/src/utils/text.ts`, wired as the
Select/MultiSelect/TagsInput default filter in `theme.ts`).

**Repeated keys (`IN`).** The first consumer of the `repeatedValues` family is `GET /api/v1/reports/deep-dive` (`reports/DeepDiveSelection.kt`):
`sprintId` (`repeatedLongs`, 1..52, ids of at least 1), `epicId` (`repeatedStrings`, 1..50) and `issueId` (`repeatedStrings`, 1..500) repeat
as `?sprintId=1&sprintId=2`, documented in the spec as `type: array` with `style: form`, `explode: true` and `maxItems`; duplicates collapse
(`distinct()`) and the bound applies to what is left, every other parameter of the endpoint stays strictly single-valued.
The second consumer is `GET /api/v1/reports/commitments`'s `accountId` (`repeatedStrings`, 1..8, opt-in through `parseReportFilter(..., multipleAccounts = true)`,
declared inline on that path as `type: array` with `style: form`, `explode: true`, `maxItems: 8`): on every report that does not opt in `accountId` stays a
`singleValue` and a repeat is a `400`. The third consumer is `GET /api/v1/reports/throughput`'s `accountId`, the very same opt-in and inline
declaration (`MAX_PICKED_ACCOUNTS` = 8 in `reports/ReportFilter.kt`). The fourth is `GET /api/v1/reports/reported-time-ratio`'s `accountId`
(the same opt-in and inline declaration), whose `Distribution` histograms then split per picked person (`byUser`).
The fifth is `GET /api/v1/reports/cycle-time`'s `accountId`, the same again (`elapsedDays` and `workingDays` split; its `trend` stays the union).
The sixth is `GET /api/v1/reports/estimate-adjustments`'s `accountId`, the same again (the tasks' `changeDistribution` splits; its `epics` block stays the all-zero USER block).
The seventh is `GET /api/v1/reports/task-estimation-accuracy`'s `accountId`, the same again (`atStart` and `atDone` split; the accuracy measures stay the union).
The eighth is `GET /api/v1/reports/wip`'s `accountId`, the same again (USER level is computed at query time and every `series` point carries a `byUser` split; `counts` stays the union).
The drill routes opt in as well: `GET /api/v1/reports/throughput/items` mirrors its parent's `accountId`, and `GET /api/v1/reports/sprint-consistency/items` takes several `accountId`s
although its parent (`sprint-consistency`) is single-account, because Commitments' and Throughput's stacked USER-level segments need them (same inline declaration, `segmentAccountId` must be one of the picks).

**The report drill lists are the one report-shaped exception.** `GET /api/v1/reports/<report>/items` (the items behind one bar of a
chart, `.claude/docs/reports.md` "Drill to items") answers `{meta, bar, items[<=500], total, truncated}`, not the paged envelope: the
fixed `{items, page, pageSize, total}` cannot carry the `meta` and the server-recomputed `bar` the page prints and reconciles against,
the full fetch and assignment run in the JVM regardless, and the bound is a hard cap with a `truncated` flag, so paging buys nothing.

**Sub-collections and facets** are not implemented yet — a record's own unbounded sub-collection
(e.g. a future audit-trail read) should be paged like any other list; reserve the plain `{items}`
wrapper for genuinely bounded, admin-curated sets. Facet counts (`GET .../facets` alongside a
list, taking the same filter parameters) are a pattern to port from Covenant's contract catalog
once a filterable list needs them — not before.
