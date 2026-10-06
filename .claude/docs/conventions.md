### Feature conventions

The shape every new feature follows; read in full before adding a feature, route, service, migration or page.

**Feature template — copy `teams/` (a small ADMIN-curated registry with a roster)**: it is the
only shape the foundation ships. `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`)
with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling
`<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes
under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on
every mutation, authorization BEFORE body decoding so 403 wins over 400 — declarative and flat; past
~100 lines it delegates to private `Route.xxx(deps)` functions grouped by concern rather than
splitting into more files, which is exactly what `config/detekt/detekt.yml`'s `LongMethod`
(threshold 100) and `CyclomaticComplexMethod` (excludes `*Routes.kt`) overrides protect),
`<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`,
soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one
predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`),
spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages +
`NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc +
coverage-map line. Fuller shapes (sub-collections, pipelines) live in `ingest/`, `metrics/`, `reports/`.

**Two deliberate exceptions to the template.** (1) `metrics/`'s config PUT (`DataSourceMetricsConfig.kt`) validates in the
service, not in a DTO validator the route also runs: `validateDataSourceMetricsConfig` is pure but needs the connection's
reference data (`norm`, the stored profile, the active teams), which `MetricsConfigService.replaceConfig` reads in its own
transaction; the route only sanitizes (trim/control characters) before the service. Its shape bounds (column widths, `capacityMd`)
sit in the same function. (2) `ingest/` has one route file per sub-resource (`DataSourceRoutes`, `SyncJobRoutes`,
`SyncStatusRoutes`, `DataProfileRoutes`, `RawIssueInspectorRoutes`) instead of one `<Entity>Routes.kt` — the
connector registry, the job queue and the read-only views differ in shape and guard.
