# Frontend (`web/`)

Vite + React 19 + TypeScript SPA: the shell + auth, user/feature management, MFA, password reset,
the changelog, and the flat-teams registry — v0.1.0's foundation — plus, since v0.2.0, the Data
sources pages (ADMIN-managed Jira Cloud connections, their sync jobs, data profile and raw-issue
inspector — see "Data sources" below) and, since v0.3.0, the metrics configuration pages and the
fifteen report pages (sixteen reports; `pages/Report*.tsx`; `pages/Home.tsx` is the unit overview — see "Home overview" under Reports). Routes are lazy. New capability that Covenant, Toadie or
Lettuce already has? Port their building blocks (see "Not yet ported" at the bottom) rather than
inventing new ones.

- Dev server: `cd web && npm run dev` (port **5176** — not Vite's 5173 default, so Flow can run
  beside Lettuce's 5173, Toadie's 5174 and Covenant's 5175). All backend routes live under the
  `/api/` namespace and Vite proxies the single `/api` subtree → `http://localhost:8084`. Any
  other path is served as `index.html` so React Router owns the SPA URL space and browser reloads
  don't collide with API routes.
- Production build: `cd web && npm run build` → static files in `web/dist`. In the Docker image
  these are baked in and served by the Ktor server itself (via `WEB_STATIC_DIR`; see
  `plugins/Routing.kt`), so production is single-origin and there is no Vite proxy — the SPA and
  `/api` share `http://localhost:8084`.
- Regenerate API types: `cd web && npm run gen:api`. Reads
  `server/src/main/resources/openapi/documentation.yaml` directly (no server needed) and writes
  `web/src/api/schema.ts`. Run this after editing the OpenAPI spec; commit the regenerated
  `schema.ts` in the same change.
- **Quality gates (the frontend counterpart of the backend's detekt)**: `npm run lint` carries
  `eslint-plugin-sonarjs` (recommended) plus core size/complexity backstops — zero-findings gate;
  rule tuning lives in `eslint.config.js` ONLY, one commented override per deliberate idiom, and
  any inline `eslint-disable` needs a justifying comment. `npm run knip` is the dead-code gate
  (unused files/exports/dependencies; `knip.json` ignores the generated `schema.ts` and the
  `ajv` devDependency pinned only for `@stoplight/spectral-cli`'s resolution) — keep exports that
  only the declaring file uses un-exported, and delete what knip flags rather than ignoring it.
  **The API-contract gate**: `npm run lint:api` Spectral-lints the OpenAPI spec and
  `api-guidelines/examples/conformant.yaml` against `api-guidelines/api-guidelines.spectral.yaml`
  (the ruleset is the other team's area — a finding here is fixed in the spec, not the ruleset);
  `npm run check:api` (`scripts/check-api.mjs`) regenerates the API types in memory and diffs
  them against the committed `src/api/schema.ts` — it must never overwrite that file, so
  `gen:api` stays the one command developers run to update it.
- **Build version stamp**: `vite.config.ts` injects `__APP_COMMIT__` (short sha, `+dirty` when the
  worktree has uncommitted changes) and `__APP_COMMIT_TIME__` (commit ISO timestamp) via `define`,
  declared in `src/vite-env.d.ts`. Env vars `GIT_SHA`/`GIT_COMMIT_TIME` override the local-git
  lookup — the Dockerfile's SPA stage sets them explicitly (its worktree never matches the index,
  so the dirty check would false-positive), and CI can do the same when building without `.git`.
  `src/components/VersionStamp.tsx` renders `v<APP_VERSION> · <sha> · <time>` at the bottom of the
  navbar (`App.tsx`) and under the login card (`components/AuthCard.tsx`).

## Layout conventions

- **Flat directories**: `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`,
  `locales/{en,pl}/`, `test/` — no deeper nesting, no per-feature folders (a feature contributes
  files into these).
- **Default exports for components/pages**, named exports for everything else; **no path
  aliases** — relative imports only.
- **Co-located tests**: `Foo.test.tsx` sits beside `Foo.tsx`; shared test scaffolding lives in
  `src/test/` (`setup.ts` forces `en`, `render.tsx` is the provider wrapper — it and every
  file-local `MantineProvider` must pass `env="test"`, or Select/Popover interaction silently
  fails under happy-dom; `http.ts` holds the fetch stubs).
- **CSS modules only where the theme can't express it**: styling belongs in `src/theme.ts`
  (Mantine `createTheme` — component `extend`s, defaultProps) with `src/theme.module.css` for the
  class-level parts; a per-component `*.module.css` is the exception, not the pattern.
- Pages are **lazy** (`React.lazy` in `App.tsx`); new routes register above the `path="*"`
  NotFound catch-all (LAST child, never feature-gated). **The nav model lives in
  `utils/navigation.ts`**, shared by the sidebar and the command palette: `NAV_SECTIONS` —
  labelled, always-open sections (currently Overview, Reports and Administration) of `NavLeaf`s (the
  `label` is a typed i18n key; `adminOnly?` gates admin leaves, and `visibleSections(isAdmin())`
  drops an emptied section) — plus `ACCOUNT_NAV` (Change password, Changelog), which the header
  `UserMenu` and the palette render and the sidebar never does. Sections are static captions
  (`role="group"` + `aria-label`), never toggles: every leaf is always in the DOM, so tests and
  e2e address the links directly; leaf clicks close the mobile drawer; `activeNavPath` is the
  longest-prefix matcher over the leaves. **The icon rail (Lettuce's):** a header `ActionIcon`
  beside the Burger (`appShell.toggleNav`, `data-expanded` while expanded) narrows the desktop
  navbar from 240 to 64px — `rail = navCollapsed && !opened`, remembered under
  `flow.viewSettings.appShell.navCollapsed`; in rail mode each leaf is an icon-only `NavLink`
  (`classes.railLink`) whose `aria-label` IS the label (so every by-name locator keeps working)
  inside a right-hand `Tooltip`, section captions become hairline `Divider`s. The mobile overlay
  always shows full labels.
- **The shell header** is 48px: brand, the `CommandPalette` trigger, and the `UserMenu` (avatar →
  identity from `GET /users/{id}` under the `["user", id]` key, language, theme as a three-state
  SegmentedControl, Change password, Changelog with the what's-new badge, Sign out). The version
  stamp stays in the sidebar footer. **`components/CommandPalette.tsx`** wraps
  `@mantine/spotlight` (pinned to the core version — keep every `@mantine/*` on one version) over
  a module-level store (`utils/commandPalette.ts`): the session's pages today. No
  `highlightQuery` — it splits labels into `<mark>` fragments that role/name locators cannot
  match.
- **Page chrome**: every page is a `Stack gap="md"` on the canvas starting with
  `components/PageHeader.tsx` (`title` → the `order={2}` heading tests locate, `description?`,
  `backTo?`, right-aligned `actions?` — the ONE place a page's primary "New …" button lives — and
  a `toolbar?` row). No `Container` and no page-wrapping `Paper`; widths come from
  `utils/layout.ts`: full-bleed lists, `Box maw={CONTENT_MAX_WIDTH}` for registry tables and the
  changelog, `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` (left-aligned) for the simple field
  forms. The one spinner is `components/LoadingBlock.tsx` (`TableLoadingRow` wraps it);
  `components/EditPageLoadState.tsx` is the edit pages' shared load-failure triage (centered
  loader, else a back-to-list alert); `EmptyState` takes the Tabler icon COMPONENT and sizes it
  itself.

## The typed API layer (`src/api/`)

The OpenAPI spec at `server/src/main/resources/openapi/documentation.yaml` is the contract between
backend and frontend — hand-maintained, not auto-generated from routes. Swagger UI is at
`http://localhost:8084/openapi` (dev). The layer is small hand-written modules:

- **`http.ts`** (transport): `authedFetch()` with the **single-flighted silent refresh** — on a
  401 it exchanges the stored refresh token once (concurrent 401s from the same session share one
  in-flight `/refresh` call) and retries once; a DEFINITIVE rejection (no refresh token, or
  401/403 from the server) clears the originating session and signs out only if that session is
  still current, while a TRANSIENT failure (network/timeout/5xx/429/malformed body) keeps the
  session so a later retry can succeed — **never collapse the two** (a network blip must not
  erase a valid session). Stale requests and refresh completions must not publish credentials,
  clear a newer login or retry across session identities. `ApiError` (status + parsed body; read
  RFC 7807 fields via the `detail`/`instance` getters, never hand-cast `err.body`), and the two
  standard wrapper shapes **`jsonRequest<T>()`/`voidRequest()`** every ordinary endpoint wrapper
  uses, plus **`buildQuery()`** — the query-string builder behind the list wrappers (skips
  null/undefined/""; `false` and `0` ARE sent, so an omit-when-false param is passed as
  `value || undefined` at the call site). Reach for the raw `authedFetch`/`safeJson` primitives
  only when a wrapper genuinely inspects the Response. Every transport fetch carries
  `timeoutSignal()` (30 s, feature-detected for happy-dom) — a hung request rejects with a
  `TimeoutError` DOMException instead of pending forever; new `fetch` call sites in `api/` must
  attach it.
- **`session.ts`**: token/roles/userId storage under the `flow.auth.*` localStorage keys, written
  by `persistSession(LoginResponse)` on login/MFA and conditionally by `persistRefreshedSession`
  on refresh, and the render-time accessors (`isAdmin()`, `getUserId()`, …). Components whose
  controls or queries depend on ADMIN use `useAdmin()` from `auth.tsx`; it subscribes to role
  changes from silent refresh and other tabs. A random stored identity distinguishes sign-ins,
  while credential snapshots guard refresh publication; renewing tokens preserves the logical
  session so staggered 401s can reuse the new token.
- **Session boundary** (`components/SessionBoundary.tsx` + `utils/sessionQueryCache.ts`): bind the
  root `QueryClient` to logical session identity changes, and key the mounted app subtree by that
  identity. A different sign-in, definitive automatic sign-out, explicit sign-out, or a change
  from another tab must synchronously cancel and remove caller-scoped query data and discard
  mounted form/mutation results before the next account renders; an access/refresh token rotation
  within the same identity must retain them. Keep this at the session boundary rather than
  clearing individual page keys, because user/team/account queries all contain caller-specific
  projections. Late requests from the previous identity must not repopulate the new account's UI
  or cache.
- **`auth.ts`**: login + the MFA exchange, self-service password reset, and logout (`logout()` is
  best-effort — local credentials are cleared immediately, before awaiting the revoke POST for the
  captured token pair; a delayed completion never clears a later login, and the function never
  throws).
- The types come from the generated `schema.ts`; a new endpoint's wrapper goes into its feature's
  module (a new feature area gets a new module — never a catch-all file). Avoid heavyweight client
  generators (Orval/Kiota) — the lightweight pairing of `openapi-typescript` (types only) +
  hand-written fetch is intentional.

Explicit sign-out opens `/login` without a saved return destination, so the next sign-in opens
Home even when a different account signs in. Anonymous access to a protected link still preserves
its path, query and hash. Test logout navigation with the real route guards from a non-home page;
a root-only menu test cannot detect a stale return destination. Reading the signed-out notice
during render must be side-effect free. Acknowledge it only after the login page commits, so a
suspended or retried render cannot discard the notice.

`openapi-typescript` is installed with `--legacy-peer-deps` because its declared peer is TS `^5`
while the scaffold uses TS 6; the generated output is compatible. If you re-`npm install` from
scratch, use `npm install --legacy-peer-deps`.

## Error handling

- **ErrorBoundary** (`components/ErrorBoundary.tsx`): a page render crash must never white-screen
  the app. `RouteErrorBoundary` wraps the `<Outlet />` inside `AppShell.Main` (header/nav survive;
  keyed by `location.pathname`, so navigating anywhere recovers), and a plain `ErrorBoundary` in
  `main.tsx` is the last resort for shell crashes. Don't add per-page boundaries — the two mounts
  are the model.
- **Catch-all 404**: `pages/NotFound.tsx` is the LAST `path="*"` child of the Shell route. New
  routes go above it.
- **Chunk-load recovery**: `main.tsx` listens for `vite:preloadError` (a redeploy 404s the old
  hashed chunks) and reloads once, rate-limited via sessionStorage (`flow.chunkReloadedAt`, max
  one reload/minute) so a genuinely missing chunk falls through to the ErrorBoundary instead of
  looping.
- **Query retry policy**: the `QueryClient` in `main.tsx` uses `shouldRetryQuery` (`api/http.ts`)
  — NEVER retry a 4xx (the answer won't change; retrying only delays the error UI), at most two
  retries for transient failures.
- **Messages**: never render `error.message` (it's the internal `API <status>` / the browser's
  "Failed to fetch") — map statuses to i18n keys via the shared mappers in `utils/saveError.ts`:
  `saveErrorMessage(err, t, keys)` for mutations (per-status keys + a `failed` fallback) and
  `loadErrorMessage(err, t)` for list loads. Errors render inline as `color="red" variant="light"`
  Alerts — never as toasts. A failed QUERY renders through `components/ErrorAlert.tsx`
  (`error`, optional `title`), never an inline `<Alert>{loadErrorMessage(…)}</Alert>`.
- The `@mantine/notifications` host is mounted in `main.tsx` (top-center, autoClose 2500, limit 3
  — deliberately not in App, so unit tests never mount it). **Success toasts only, with fixed
  vocabulary only** (`showSuccessToast(t("<area>.toast.*"))` in `utils/toast.tsx` — teal, never
  user-entered values; errors stay inline).

## Shared list-page building blocks (the Users.tsx template)

Every list page composes the same ported Lettuce blocks — copy `pages/Users.tsx`, don't re-derive:

- **State**: filters in `useStoredState` (persisted under
  `flow.viewSettings.<viewKey>.filter.*`, text filters debounced 300 ms — the DEBOUNCED value
  goes into the query key, and any action reading the filters reads the DEBOUNCED value too, so a
  click never operates on a slice the table isn't showing), sort/page/pageSize from
  `usePagedSort(initialSort, filterDeps, { key, sortFields })` with `SORT_FIELDS ... as const`.
  Every query derived from one area keys under that area's prefix (`["users", …]`, `["teams", …]`),
  so refreshing an area prefix after a mutation refreshes all its active queries and invalidates
  inactive cached data.
- **Query**: `useQuery({ queryKey: ["<area>", page, pageSize, sortParam, ...filters], queryFn: list<Area>(...), placeholderData: keepPreviousData })`.
- **Chrome**: `FilterPanel` (collapsed by default, persisted, active-count badge) +
  `ClearableTextInput` for freetext filters (debounced); `SortHeader` renders its OWN `Table.Th`
  (so the sort state lands as `aria-sort` on the header cell — don't wrap it in another Th);
  `TableLoadingRow` (`isLoading && !data`) / rows / `EmptyState` (`!isError`) triage in the tbody;
  a `color="red" variant="light"` Alert with `loadErrorMessage` ABOVE the table on error;
  `PaginationBar` below.
- **The colour vocabulary is app-wide, not per-page**: red = hard/blocking (validation errors,
  destructive confirm buttons, a failed row), orange = a soft finding (a drift badge, a data-quality
  `Found: N`, an unmapped stage, a tiling anomaly), teal = success, gray = neutral state, blue = in
  progress (the one status use of blue: a RUNNING job badge, the WIP in-progress band). A new status colour goes through
  that vocabulary, not a page-local pick.
- **Delete**: `useDeleteConfirm` + `ConfirmDeleteModal` — the hook owns modal state and the
  success toast, the page owns cache refresh. For mutations while a list remains mounted, use
  `refreshQueriesAfterMutation(queryClient, ["area"], ...)` from `utils/queryRefresh.ts` after
  success. It cancels all affected query groups before invalidating them: a newly keyed initial
  fetch has no cached data even when `keepPreviousData` displays rows, so default invalidation
  alone may reuse a pre-mutation response. Keep refresh out of failed mutation paths.
  **`ConfirmActionModal`** is the non-destructive sibling (Lettuce's, ported with the
  feature-flags bulk actions) — a message plus a neutral cancel and a confirm button whose
  `loading` blocks cancel/close while the action runs; labels arrive already translated.
- **An entity's NAME is the way into its detail page** — a `RouterLink` `Anchor` with an
  interpolated accessible name (`common.action.editAria` = "Edit {{name}}", or an area-specific
  `openAria`), which is what tests and e2e locate. A real link, not an onClick: cmd/middle-click
  opens a tab and it takes keyboard focus. Route families are spelled out ONCE in
  `utils/<area>Links.ts` — never hand-assemble a URL.
- Row action buttons carry interpolated aria-labels (`<area>.editAria` etc.) — unit tests and e2e
  locate by them; table tests query cells by **text**, not `cell` role names. **Row actions**: a
  row with more than two actions bundles them under `components/RowActionsMenu.tsx` — an
  icon-only kebab whose accessible name is `common.table.operationsAria` ("Operations for
  {{name}}"; items are plain `menuitem`s, only one row's menu is open at a time); a per-row
  `Switch` (Feature flags) is state, not an action.

## Registries (the Teams.tsx template)

The small ADMIN-curated registries (Teams, and since v0.2.0 Data sources; more arrive with the
Jira domain model) compose
`useRegistryListControls` for their persisted name filter, debounce and paged sort, and
`RegistryListTable` for the common load/error/empty/pagination states. Each page owns its query
key and parameters, extra filters, columns, row actions and mutation refresh prefixes. Their
editors (e.g. `components/TeamEditorModal.tsx`) share the `RegistryMetadataFields` (name +
description, with `utils/charCount.tsx`'s "123 / 4000" counter) and `RegistryEditorActions`
(save-error alert + cancel/submit footer) components; form rules
(`utils/formRules.ts`'s shared `nameRule`/`descriptionRule`), submit/conflict handling and any
parent/target fields stay local. Keep each registry's field limits explicit and mirrored from the
server's own validation (`infra/validation/Text.kt`'s `requireNameAndDescription`).

## Forms (the CreateUser/EditUser template)

- Shared vocabulary in `utils/<area>Form.ts`: the `<Area>FormValues` type, length constants
  mirroring the server's, a `<area>FormValidation(t)` factory (rules identical to the server's —
  keep them in sync), and `toRequest`/`fromResponse` mappers. The field block lives in
  `components/<Area>FormFields.tsx`; the pages own submit/error/navigation.
- Edit prefills via **`form.initialize(...)` guarded by `!form.initialized` during render** —
  never a `useEffect`. Submit is a plain async fn with local `submitting`/`error` state wrapped by
  `form.onSubmit`; success → `invalidateQueries` (list + detail) → `showSuccessToast` →
  `navigate(list, { replace: true })`; failure → `saveErrorMessage` into an inline Alert.
- **Widths**: `Paper withBorder p="xl" maw={FORM_MAX_WIDTH}` under a `PageHeader` (with `backTo`
  to the list) for simple field forms — left-aligned, never a centred `Container`.

## User management (`pages/Users|CreateUser|EditUser|ChangePassword.tsx`)

Passwords are generated client-side (`utils/password.ts`) and revealed exactly once: the modal
(`components/OneTimePasswordModal.tsx`) sets `closeOnClickOutside={false} closeOnEscape={false}`,
renders `components/RevealablePassword.tsx` (masked by default, eye toggle, copy), and closing
drops the plaintext from state for good — the server never returns it. The admin Reset-password
action (`hooks/useResetPassword.ts`) reuses the same generate→PUT→reveal flow. `NavLeaf.adminOnly`
gates the Users nav item (the Shell filters by `isAdmin()`); the management routes sit under ONE
`<Route element={<RequireAdmin />}>` in `App.tsx` (`auth.tsx`, beside `RequireAuth` — a regular
user is sent home), so no page redirects itself; their queries additionally stay
`enabled: isAdmin()` so a redirected caller fires no request. UX only — the server's
`requireAdmin` is the rule.

## Feature flags (`pages/FeatureFlags.tsx`, `pages/UserFeatures.tsx`)

Lettuce's per-user flag surfaces, ported with the MFA feature: `hasFeature()` (`api/session.ts`,
reading the `flow.auth.disabledFeatures` localStorage set — currently awaiting its first
area-gating consumer; MFA gates only the login flow server-side), the per-user editor at
`/users/:id/features` (the Users table's Features button — a checkbox per `Feature`, wholesale-
replace PUT), and the ADMIN per-feature screen at `/feature-flags` (`adminOnly` nav leaf; state
filter + bulk enable/disable over every row matching the current filters behind a count-stating
confirm — `hooks/useBulkFeatureUpdate.ts` loops the same per-user wholesale PUTs client-side via
`ConfirmActionModal`). Both queries key under `["users", …]`. Mind the wholesale-replace
semantics: a PUT whose disabled set omits `MFA` ENABLES it.

## Data sources (`pages/DataSources|DataSourceDetails|DataSourceProfile|RawIssueInspector.tsx`)

The first Jira-domain surface (v0.2.0 plan §9/§10), ADMIN-only end to end: the nav leaf
(`IconPlugConnected`, `adminOnly`), the four routes (`/data-sources`, `/data-sources/:id`,
`…/:id/profile`, `…/:id/inspect`, all under the same `RequireAdmin` group as Users/Feature flags
in `App.tsx`; every page's queries additionally stay `enabled` only for `useAdmin()`), and every
endpoint (`requireAdmin` server-side).

- **`api/dataSources.ts`** mirrors `api/teams.ts`'s shape — list/create/update/delete plus the two
  Test-connection wrappers (`testDataSourceAdHoc`/`testDataSourceStored`) and `requestSyncJob`
  and the per-connection reads/actions: `listSyncJobs`/`cancelSyncJob` (`…/sync-jobs`),
  `getDataSourceStatus` (`…/status`), `getRawIssue` (`…/raw-issues/{issueKey}`) and
  `getDataSourceProfile` (`…/profile`) — every type derived from `schema.ts`. Unlike Teams, `DataSourcePage.items` already carries the FULL `DataSourceResponse`
  (minus the write-only token) — Edit opens straight from the row, no extra detail fetch.
- **`pages/DataSources.tsx`** is the Teams registry template (`useRegistryListControls` +
  `RegistryListTable`), sorted by name only (the server's other sortable fields — `id`,
  `createdAt`, `updatedAt` — have no visible column). Columns: name, site host (parsed from
  `jira.siteUrl` — always a bare origin, so `new URL(...).host` is safe), projects (joined
  keys), enabled (plain Yes/No text — the state badge below is the one place this page spends
  colour), last success (`YYYY-MM-DD HH:mm` sliced from the ISO string like `VersionStamp`, never
  `toLocaleString()` — deterministic across test/CI locales; "Never" when null), and the
  `DataSourceState` badge — teal `CURRENT`, red `FAILED`, gray everything else
  (`NEVER_SYNCED`/`STALE`/`DISABLED`), the app's existing success/blocking/neutral vocabulary, no
  new hue. Row actions (`RowActionsMenu`): **Sync now** (`POST …/sync-jobs {kind: SYNC}`, a direct
  action with no confirm step — the success toast itself distinguishes a freshly queued job from
  `coalesced: true`; a failure renders inline above the table, same rule as everywhere else:
  never a toast), Edit, Delete (`ConfirmDeleteModal`, the same conflict-naming pattern as Teams).
- **`components/DataSourceEditorModal.tsx`** ports Covenant's `ToadieConnectionEditorModal` (the
  site URL disables once a connection exists — Toadie's `baseUrl` pattern — since a changed
  `siteUrl` is a `409` server-side; that also means a saved `409` here is always the name clash,
  so it marks the `name` field like Teams does). Fields: name, site URL (`https://<tenant>
  .atlassian.net` hint), service-account email, a `PasswordInput` token ("leave blank to keep the
  current token" on edit — blank travels as an omitted `apiToken`, which the server keeps), a
  `TagsInput` for project keys (force-uppercased in `onChange`, after `form.getInputProps`, so the
  wire value always matches `PROJECT_KEY_PATTERN`), a plain backfill-date `TextInput`
  (`YYYY-MM-DD`, blank omits the field so the server computes its 24-months-back default — no
  `@mantine/dates`), sync interval / reconcile hour `NumberInput`s, an auth-scheme `Select`
  (Basic/Bearer), and an enabled `Switch`. **`utils/dataSourceForm.ts`** mirrors
  `ingest/DataSource.kt`'s validation exactly (name/site-URL/email/token length caps, the Jira
  site-URL and project-key regexes plus the Atlassian-reserved-label rejection, the
  backfill-date range) — keep the two in sync.
- **Test connection**: the same button drives both the ad-hoc probe (`POST /data-sources/test`,
  the form's current values — required whenever creating or rotating the token) and the stored
  probe (`POST /data-sources/{id}/test`, no body) when editing with a blank token field; it
  validates only the four Jira fields the probe needs (not the whole form) before calling.
  **`components/ConnectionTestResults.tsx`** renders the row table (endpoint + path / required /
  a teal-or-red result badge / a detail cell combining the upstream status, the
  `JiraFetchException` code and a failed row's `scopeHint`) and the resolved `cloudId` once
  `tenant_info` succeeds.
- **`pages/DataSourceDetails.tsx`** (`/data-sources/:id`, reached from the list's name link or its
  Open row action) is the connection's operational view over ONE `GET …/status` query: summary
  (state badge via `utils/dataSourceState.ts`), the current job (`JobStateBadge` + stream +
  progress counters), `components/CursorTable.tsx` (each cursor's `position` rendered verbatim —
  the server owns its shape) and the raw-store counts, plus the paged sync-jobs history
  (`components/SyncJobsTable.tsx` on the `RegistryListTable` shell, kind/status filters, a Cancel
  per still-open row). **Auto-refresh is conditional**: the status query's `refetchInterval` is 5s
  only while `currentJob` is PENDING/RUNNING, `false` otherwise — never a fixed poll. Header actions
  Sync now / Reconcile (direct, the toast distinguishes `coalesced`), Reprocess (behind
  `ConfirmActionModal` — it rebuilds every normalized row), Cancel on the open job (direct; a `409`
  means it finished meanwhile and renders inline, never a toast), Edit (the same
  `DataSourceEditorModal`), and links to the profile and the inspector. A `404` or load failure goes
  through `EditPageLoadState` with a back link, like the user editors.
- **`pages/DataSourceProfile.tsx`** (`…/:id/profile`) renders `GET …/profile` as plain Mantine
  tables, one per section (projects, workflows with observed-vs-reference statuses, boards with
  unmapped statuses, custom fields with fill rate/role, estimates, worklogs, reopens, sprints,
  people, anomaly counts) — deliberately no charts until phase 3's dashboards. `computedAt: null`
  (no PROCESS pass yet) is an `EmptyState`, not an error; percentages print with one decimal,
  matching the server's rounding.
- **`pages/RawIssueInspector.tsx`** (`…/:id/inspect?key=`) — the looked-up key lives in the URL
  (`useSearchParams`), so a lookup is a shareable deep link (`dataSourceInspectPath(id, key)`); the
  query only runs once a key is present. Shows the canonical raw payload, tombstones, changelog/
  worklog payloads, and — once processed — the `norm.*` work item, its status/field intervals and
  anomaly badges (orange, the fixed `TilingAnomaly` vocabulary). A `400`/`404` renders INLINE under
  the form, never replacing the page, so the admin can simply try another key.
- **`utils/dataSourceLinks.ts`** is the ONE place the route family is spelled out
  (`dataSourcesPath`, `dataSourcePath`, `dataSourceProfilePath`, `dataSourceInspectPath`) — never
  hand-assemble these URLs. `utils/dataSourceState.ts` holds the state→colour map and
  `formatEpochMillis` (the deterministic `YYYY-MM-DD HH:mm` rendering, "Never" for null).
- `pages/Home.tsx`'s admin empty state links to `/data-sources` and `/metrics-settings` (plain `Anchor`s
  under the `EmptyState`, not a rewrite of that shared component) — see "Home overview" under Reports.

## Metrics configuration (`pages/MetricsSettings.tsx`, `components/TeamJiraMembers.tsx`, `pages/DataSourceMetricsConfig.tsx`)

The v0.3.0 metrics-configuration surfaces: the global settings singleton and D1's dated Jira-user
team membership landed in M2 commit 5, and the per-connection `metrics-config` page in commit 6.
`api/metrics.ts` mirrors `api/teams.ts`'s thin-wrapper shape: `getMetricsSettings`/
`updateMetricsSettings`, the `/jira-memberships` CRUD, `listJiraUsers`, and
`getDataSourceMetricsConfig`/`updateDataSourceMetricsConfig`/`getDataSourceMetricsConfigOptions`
— every type derived from `schema.ts`.

- **`pages/MetricsSettings.tsx`** (`/metrics-settings`, `adminOnly` nav leaf `IconAdjustments`,
  under the same `RequireAdmin` route group as Users/Data sources) is the ONE global
  `metrics.settings` singleton form — the CreateUser/EditUser template (`Paper withBorder p="xl"
  maw={FORM_MAX_WIDTH}`, `form.initialize` guarded by `!form.initialized`, a full-replace PUT,
  `showSuccessToast` on save, an inline Alert on failure). `utils/metricsForm.ts` mirrors
  `metrics/MetricsSettings.kt`'s `validateMetricsSettings` field for field (including the
  aging-percentiles-must-include-85 rule and the all-weekend rejection) and holds the form's own
  string-array reshaping: `weekendDays`/`agingPercentiles` travel as `Chip.Group`/`TagsInput`
  string values client-side, mapped to `number[]` only in `toMetricsSettingsRequest`. The zone
  picker is a searchable `Select` over `Intl.supportedValuesOf("timeZone")`
  (`utils/metricsForm.ts`'s `supportedTimeZones`, cached). The success toast reads "Saved — reports
  re-derive shortly" — accurate once M3's DERIVE job lands, so the wording needs no follow-up
  change.
- **`components/TeamJiraMembers.tsx`**, mounted on `pages/TeamDetails.tsx` below the Flow-login
  roster — D1's dated Jira-user team membership (`GET/POST /api/v1/teams/{id}/jira-memberships`,
  `PUT/DELETE …/{membershipId}`): any authenticated user reads the table (person name resolved
  against `GET /api/v1/jira-users?scope=UNIT` — every account that ever held a membership row is
  UNIT-relevant, so one page of that directory names this team's whole history; an unresolved
  account falls back to its raw `accountId`), a "current" badge when `now ∈ [validFrom, validTo)`.
  ADMIN-only mutations: "Add Jira member" opens `components/JiraMemberModal.tsx` (a searchable
  person `Select` over `GET /api/v1/jira-users?scope=SITE` — the whole site directory, since a
  brand-new member may not yet be UNIT-relevant — plus a required valid-from and optional valid-to
  date; the directory query shows a labelled `Loader` (`role="status"`) and `aria-busy` while pending and
  an inline red `Alert` (`loadErrorMessage`) on failure — "No matching people" only states a completed
  search for the current term, never during the debounce window or a pending/failed load; the
  exclusion-constraint `409` renders inline in the modal, never a toast), a per-row "End
  membership" (only on the open-ended row — direct PUT setting `validTo` to today's UTC midnight,
  the `startOfTodayEpochMillis` helper) and Delete (`ConfirmDeleteModal`, the `useDeleteConfirm`
  precedent). **Dates are plain `YYYY-MM-DD` `TextInput`s** (`utils/isoDate.ts`'s
  `isValidIsoDate`/`isoDateToEpochMillis`/`epochMillisToIsoDate`, UTC throughout — the
  `dataSourceState.ts` `formatEpochMillis` convention — no `@mantine/dates` dependency in this
  commit; it arrives with the Reports period picker, §2.4 of the phase-3 plan).
- **`pages/DataSourceMetricsConfig.tsx`** (`/data-sources/:id/metrics-config`, under the same
  `RequireAdmin` group, reached from a "Metrics configuration" toolbar link on
  `DataSourceDetails.tsx` beside Profile/Inspect) edits the ONE composite
  `DataSourceMetricsConfig` resource (`.claude/docs/metrics.md` "Per-connection metrics
  configuration") over `Tabs`: Statuses (stage `Select` + a Blocked `Checkbox` per status, the
  Jira category as a `Badge`), Fields (five `Select`s over the profile-detected custom fields plus
  Jira's own `duedate` system field, labelled with the detected role), Domains (project key →
  domain key/name), Boards → team (an active-teams `Select`; a `409` marks the changed board
  row(s) inline, the "which row" rule computed by diffing the just-submitted board→team snapshot
  against the last-loaded/last-saved one — `utils/metricsConfigForm.ts`'s `changedBoardIds`),
  Activity types (issue type → activity type) and Work categories (value → category, fetched with
  `?workCategoryField=` the moment a work-category field is chosen on the Fields tab — shown only
  once one is). Capacities (one `NumberInput` per sprint, blank = the computed default) rounds out
  the seven. GET returns computed defaults when `configured: false` — a banner says so ("Showing
  computed defaults — save to confirm them") until the first save. ONE Save does a full-replace
  PUT of every tab's current state (even an untouched tab still submits its own default), a
  success toast reads the same "reports re-derive shortly" wording as `MetricsSettings.tsx`, and a
  `400`/`409` renders the SERVER'S OWN `detail` message inline (not a fixed-vocabulary mapping —
  these messages are admin-facing by design, `.claude/docs/metrics.md`'s validation/conflict
  rules). **`components/MappingTable.tsx`** is the one generic id → editable-cell table every
  mapping-shaped tab reuses (a `Select`/`TextInput`/`Checkbox` per field column, the row's own
  identity in a fixed left column) — callers own all state, MappingTable is a pure renderer.
  **`utils/metricsConfigForm.ts`** holds the pure state-shaping: `buildInitialState` merges the GET
  response with the options endpoint's reference lists into one row per reference item (mapped or
  not), `buildRequest` is its inverse, `mergeWorkCategoryValues` combines the field-scoped values
  query with whatever category is already chosen, and `changedBoardIds` is the 409 row-marking
  rule.

## Reports (`pages/ReportVelocity.tsx`, `components/Report*.tsx`, `utils/reportFilter.ts`)

The v0.3.0 report pages (`.claude/docs/reports.md` is the API; every report is any-authenticated,
D12 — routes sit under `RequireAuth`, never `RequireAdmin`). Fifteen pages carry the sixteen reports
(report 13, the backlog in sprints, rides the Estimated backlog page): the shell (Reports nav
section, `ReportTabs`), the filter bar, the shared blocks and the three tab groups — Delivery
(Velocity, Throughput, Sprint consistency, Cycle time; `DELIVERY_TABS`), Estimation (Task accuracy,
Epic accuracy, Adjustments, Reported time; `ESTIMATION_TABS`) and Flow metrics (WIP, Estimated
backlog, Aging WIP, Blocked time, Epic progress; `FLOW_TABS`) — plus the Data quality and Cost
matrix pages. A new report appends a page, a tab (the tab lists live in
`utils/reportLinks.ts` — each group's nav leaf lists every tab route in `NavLeaf.activeFor`, so it
stays highlighted on all of them) and a `reports.<name>` key block. Every page composes the same
skeleton — `hooks/useReportPage` (filters query → keyed page query), `ReportFiltersStatus`,
`ReportFilterBar`, `ReportMetaNote`, `ReportChartCard`, `ReportSprintsTable` (sprint · team ·
completed · the report's figures · the orange drift badge with the frozen figures as text),
`ReportGroupsTable` — copy `pages/ReportVelocity.tsx`.

- **The URL is the filter** (`utils/reportFilter.ts`, `hooks/useReportFilter.ts`): `from`/`to`,
  `lastSprints`, `sprintId`, `teamId`, `accountId`, `domainView`, `domain`, `activityType`,
  `workCategory`, `breakdown`, `bucket`, `connectionId` — deep-linkable, parsed forgivingly (an invalid or
  conflicting param is DROPPED, never sent; the period is exclusive with precedence `sprintId` >
  `lastSprints` > dates), serialized in one canonical key order (that string is also the page query
  key). Params this module does not own survive `applyReportFilter`; switching report tabs
  (`reportHref`) drops the report-specific params (`domainView`, `domain`, `activityType`,
  `workCategory`, `breakdown`, `bucket`) the target report has no control for, so a filter the user
  cannot see or clear never follows them (`REPORT_SPECIFIC_PARAMS` in `utils/reportLinks.ts`; `epicId` is one of them, kept only by epic progress; a page's normalizer drops the rest through `dropReportSpecific(filter, reportSpecificKeep(path))`, so the table stays the one source). A team id the reference data no longer lists prints `#<id>` through `teamLabel` (`utils/reportFormat.ts`). Presets are stored as
  absolute `from`/`to` dates (calendar days in the configured zone) and recognised again by
  `activePeriodChoice`. **The last team is remembered** (`useStoredState`, `reports.teamId`): a report
  opened with NO filter params at all (a bare nav click) starts on it and the URL is rewritten
  (replace) to say so; a link that carries any filter param is taken as written (a copied unit-level
  link stays unit-level). Only the Team control touches the memory (picking stores, clearing clears).
- **`components/ReportFilterBar.tsx`** is a controlled component (`filters`, `filter`, `onChange`);
  optional controls (domain view "delivered in / earned in", domain, activity type, work category,
  breakdown, and the week/month `bucket` `SegmentedControl`) render ONLY where the report passes them
  in `controls` — velocity and sprint consistency pass none, throughput passes domain view, domain,
  activity type, work category and bucket (not breakdown, which it ignores). Every report is also a
  command-palette entry (`REPORT_PALETTE_LEAVES`, palette-only: the sidebar carries one Delivery leaf).
- **Distributions** (`components/DistributionPanel.tsx`): a `Distribution` renders as a percentile
  strip (median p50, p90, p95, mean, item count), a lazy `DistributionHistogram` (single blue series,
  no legend) and the histogram as a table; `hidden` (fewer items than `meta.minSampleSize`)
  replaces all of it with `MinSampleNotice` (gray, names n and the minimum — never a warning: a small
  sample is a fact about the selection). Units come in through `format`: accuracy ratios via
  `formatRatio` ("actual ÷ estimate", 1.00 = on estimate), fractional changes via
  `formatSignedPercent` (`+25%`, a true minus sign), shares via `formatPercent`. A group below the
  minimum shows its counts and a dash (`formatMedian`), never a median. `ExcludedList` is the counted, plain-language accounting of ONE distribution — rendered under the
  distribution it describes, never merged across views, because the two views' partitions differ
  (`n + Σ reasons == population`, spelled out as a closing equation line; the server's per-view
  partition, pinned on the fixtures by `reportFixtures.test.ts`, like A17). Histogram range labels
  come from `histogramLabels` (widened a decimal at a time until no two ranges print alike — no
  "1 – 1") and the x axis is titled with what the ranges measure (`axisLabel`). Numbers are never
  locale-formatted (repo convention): the ratio hint says "1", like the UI prints. Epic figures at USER
  level are one `EpicsPerPersonNote` line (epics carry no user), not an empty state or zeros. Heading
  levels: card title h3, block titles h4, distribution and accounting titles h5.
  Task and epic accuracy show the at-start view first (D15: the primary view) and the at-done view
  beside it; an epic's own estimate is never replaced by its child sum (both are columns). The epic
  accuracy page offers only domain and work category (an epic's domain is its own space under either
  domain view, and it has no activity type); the other two estimation pages offer the domain view too.
- **Cycle time and reported time**: `DistributionWithAccounting` (panel + its own `n + Σ = population`
  list) is the unit every distribution page composes. Cycle time shows working days (primary) and
  elapsed days, then the trend — a `LineChart` of p50 (solid `flow.6`) and p90 (`gray.6`, DASHED, so
  the two lines differ by dash as well as hue: the legend swatches are hue-only, so the dash, the trend
  table's column headers and the tooltip carry identity) per week/month, reusing throughput's bucket control and its
  "the report travels with the bucket that produced it" rule. A bucket below the minimum sample has
  `null` p50/p90: the line BREAKS there (`connectNulls` off — a gap, never a zero) and the trend
  table beside it prints a dash but keeps that bucket's real `n`; if no bucket is plottable a note
  replaces the empty frame. Reported time explains in its description that the ratio is "how much of
  the elapsed working time was logged" (actual MD ÷ cycle working days), distinct from flow
  efficiency (active ÷ cycle time, shown as %, no "no time logged" bucket — so the two panels keep
  separate accountings), and reads the median first (the outlier note: very short cycles dominate
  the mean, p95 and the histogram's top; they are never dropped).
- **Flow metrics group** (`pages/ReportWip.tsx`, `pages/ReportBacklog.tsx`, `FLOW_TABS`, the "Flow metrics" nav leaf
  `appShell.nav.reportsFlow` — deliberately not just "Flow", which the brand text already is): the two
  snapshot reports over the daily aggregates. Their bar offers only `domain` (never activity type/work category —
  `400` server-side) and `domainExcludesTeam` makes the LAST of team/domain touched win (the aggregate has no
  team × domain split; picking a domain therefore clears the remembered team, like any team clear), and a
  pasted link with both is normalised on load — the team wins — by `useReportPage`'s `normalize` argument
  (`dropDomainWithTeam`, `normalizeWipFilter`), which rewrites the URL too so a dropped param cannot come
  back when another control changes. WIP adds two
  managed params, `by` (stage · status · board column) and `itemKind` (tasks · epics · both), both always SENT
  explicitly (`by=STAGE&itemKind=TASK` by default); `by=COLUMN` needs one team (`wipColumnAvailable`) so the
  option is disabled with a hint otherwise and a stray `by=COLUMN` is dropped from the URL (falls back to
  STAGE), and a `400` for a request that WAS for columns of one team is the "no board mapped" note, not a
  failure (any other 400 is the normal failure alert). The chart is a stacked `AreaChart` of end-of-day counts with band
  toggles (`Chip.Group`, local state): stages keep their vocabulary colours (gray/blue/teal/orange); statuses
  and columns are arbitrary names, so they never wear a semantic hue: `BAND_CYCLE` alternates blue and gray
  (flow.6, gray.6, flow.7, gray.6 — the only neutral shades that clear 3:1 on all four surfaces), assigned by
  position among the bands SHOWN (`paintBands`) so neighbours never share a hue; beyond two bands a hue/shade
  repeats and the legend, tooltip and tables carry identity. The ticked-bands state is per keying (and per team
  for columns); STAGE starts with Not started and Done hidden (Done only
  grows, Not started is the whole backlog). Its text alternatives are a per-band summary table and the full
  daily table behind `DailyTableDisclosure` (a series can run to ~1100 rows). The backlog page is three tiles
  (MD, items, "≈ N sprints ahead" — a dash plus the reason when there is no velocity or the mean is 0,
  "< 0.1" instead of "≈ 0"; at UNIT level the wording says "at least N closed sprints per team", since the pace
  is a sum of team means and `sprintsUsed` their minimum) and a
  one-series MD `AreaChart` whose tooltip and table add the item count. The server's `note` (not derived yet,
  USER level, …) is prose in English only: `ReportNote` shows it verbatim under a translated title, except the "Not derived yet" note when
  `meta.derivedAt` is null (the translated meta line already says it).
- **Aging WIP and blocked time** (`pages/ReportAgingWip.tsx`, `pages/ReportBlockedTime.tsx`, tabs 3 and 4 of
  `FLOW_TABS`). Aging WIP is "as of now": its bar passes `noPeriod` (no period control) and the request drops
  any period param (`withPeriod(filter, {})`; the URL keeps it so tab hops preserve it). It shows the
  thresholds as a tile row per kind (`AgingThresholdsRow`; epics only when there are epics or a window; hidden
  thresholds = a `MinSampleNotice` with `subject="thresholds"`) above the open-work table, in the server's
  order (never re-sorted). **The band is the SERVER's** (`"P85"`, `"WITHIN"`, `null` = hidden) — never
  recomputed from the age; `utils/agingReport.ts`'s `bandTone` only reads its RANK among the configured
  thresholds of the item's own kind: the top threshold red ("above p95"), the next orange ("above p85"), the
  rest gray — the text always names the threshold, colour is never the only carrier. Blocked is a red outline
  badge with the word. Issue keys are plain text (no page links to Jira). Blocked time composes
  `DistributionWithAccounting` twice (blocked working days over EVERY finished item, so no exclusions and a
  "N of M were blocked at all" line; share of cycle with `neverStarted`/`zeroCycle`), the top-20 table and the
  groups table; `itemKind` (default TASK, always sent) reuses the WIP bar control, and at TEAM level with epics
  a line says the per-assignee groups cover tasks only.
- **Epic progress (EVM)** (`pages/ReportEpicProgress.tsx`, the fifth tab of `FLOW_TABS`; `.claude/docs/reports.md` "Report 15").
  The URL carries at most ONE scope — `epicId` (an epic's issue key, a report-specific managed param that only this
  report keeps), `domain` or `teamId` (`0` = UNASSIGNED) — and `normalizeEpicProgressFilter` (`utils/epicProgressReport.ts`,
  the `useReportPage` normalize hook) makes a pasted link answerable: epic over domain over team, and `domainView`,
  `accountId`, `activityType`, `workCategory` (each a `400`) plus the ignored `breakdown`/`bucket`/`by`/`itemKind`
  dropped — off the request and off the URL. The bar passes `domain` + `domainExcludesTeam` + `noMember` (no user
  level) and the page routes its changes through `applyBarChange`, so the LAST scope touched wins, epic included.
  Levels are drilled by the row NAME links (`scopedSearch` re-scopes the same report, the period travelling along); a
  domain → epic drill carries the domain in the router `state` so the breadcrumb (`EpicProgressBreadcrumb`) can offer the
  way back — a directly opened epic simply has only the unit above it. Tiles: PV/EV/AC, SV/CV signed
  (`formatSignedMd`), SPI/CPI at two decimals (`formatIndex`) — a dash plus the reason when the server sends `null`
  (PV 0 / AC 0), and above/below 1 said in WORDS (`indexVerdict`, read at the printed precision), never red or green:
  an index under 1 is a fact about the plan, not a blocking failure. TEAM adds the foreign-work share beside CPI (A20).
  The UNIT drill is two tables: the domains (the epic basis — they add up to the headline) and the teams (the sprint/
  author basis — a different view, captioned as not adding up; a soft-deleted team, `active: false`, is marked "Deleted
  team" and never linked). The chart is a `LineChart` of the CUMULATIVE PV (`flow.6`), EV (`teal.8`), AC (`gray.6`) over
  ISO dates; at EPIC level the first baseline (`pvOriginal`) is a fourth series in the plan blue but DASHED, only when
  some point has it (`connectNulls` off), and the full table sits behind `DailyTableDisclosure`. The EPIC plan panel
  shows the budget and its source, the planned dates (calendar dates, read in UTC), the orange drift badges, every
  baseline (`effectiveFrom`/`supersededAt` are instants, read in the configured zone) and the two gray "no plan curve"
  notes (`!inPvHorizon` / in the horizon but `!hasPvCurve`) — EV and AC still count either way.
- **Data quality** (`pages/ReportDataQuality.tsx`, `/reports/data-quality`, its own single-page nav leaf `appShell.nav.reportsDataQuality` —
  a group of one, so no `ReportTabs` and no palette-only twin; `.claude/docs/reports.md` "Report 14"). One card per finding
  (`components/DataQualityCard.tsx` the shell: h3 title, one plain-language line, a state badge — orange `Found: N`, teal `None found`, gray
  `Not measured`, none for the logged-hours figure — and `CappedTable`, which says "and N more" when `total > items.length`), in the order
  logging (`DataQualityLogging`) → missing data and epics (`DataQualityMissing`) → sprint/drift (`DataQualitySprint`) → configuration
  (`DataQualityConfig`), the overview tiles (`DataQualitySummary`, each a link that scrolls to and focuses its card — no hash in the URL)
  first and the groups table last. A clean finding keeps its card (users see it was checked). The bar offers period, team/member, domain,
  domain view and — only with more than one connection — `connection` (`ReportControls.connection`); `normalizeDataQualityFilter` drops
  every param the page has no control for (activity type, work category, breakdown, bucket, by, item kind, epic) off the request AND the
  URL. What the API returns is rendered as is: a real team sees no domains/authors without a team (the card says a team's view lists none),
  the connection-level findings say the team filter does not narrow them, USER level says epic and sprint findings are not read for one
  person, and a work-category field nobody configured is "Not measured", not clean. Configuration findings are admin-actionable: the
  connection cell links to `dataSourceMetricsConfigPath` for `useAdmin()` only, plain text for everyone else. Snapshot drift prints the
  sprint, translated figure, live, frozen and the signed difference (`utils/dataQualityReport.ts`), marking `reconstructed` baselines with
  an orange light badge and one explanatory line.
- **Cost matrix** (`pages/ReportCostMatrix.tsx`, `/reports/cost-matrix`, its own single-page nav leaf `appShell.nav.reportsCost` — a
  group of one like Data quality, since a matrix is a different shape from the flow reports' series, so no `ReportTabs` and no palette-only
  twin; `.claude/docs/reports.md` "Report 16"). Tiles (man-days, foreign man-days, the overall foreign share — a dash and the reason when the
  server sends `null`, never 0%), then `components/CostMatrixTable.tsx`: a HEAT TABLE — rows are the level's authors (UNIT: author teams,
  UNASSIGNED `teamId` 0 last under the shared "Unassigned" label; TEAM: the team's authors, the no-author bucket "No known author"; USER: one
  row), columns the domains (`domainView` default EPIC, the bar's "Delivered in / Earned in" toggle; the caption line says whose domain the
  columns are), cells the man-days shaded on ONE sequential scale (`utils/heatScale.ts`: the brand blue mixed into the table surface in five
  steps, linear against the largest CELL — `heatStep` — with each step's own text colour, pinned ≥ 4.5:1 in both schemes by
  `heatScale.test.ts`; a zero cell has no fill and dimmed text; the number is the carrier and a legend says so), then row total, foreign MD
  and foreign share (`formatPercent`, a dash for `null`) and a totals row. Every figure is the server's own rounded exact sum, NEVER re-added
  from the cells shown (the footnote states the ≤ 0.005-per-addend rule). It is a real table: a visually hidden `caption`, `scope="col"`/
  `scope="row"` headers, the first column and the header row sticky inside a `Table.ScrollContainer` (`theme.module.css` `.heatTable` — the
  theme's card frame clips with `overflow: hidden`, which would keep `position: sticky` from engaging, so the class lifts it). A row NAME is
  the way in (`drillSearch` in `utils/costMatrixReport.ts`: team → its authors, author → one person, the period travelling along; a one-sprint
  period survives only into a team that lists the sprint); a soft-deleted team (`active: false`) is marked "Deleted team" and never linked
  (its drill is a `400`), and UNASSIGNED is not linked under a sprint-relative period (`meta.from === null` — a team-less drill resolves no
  sprint and answers empty). A sprint-relative period says so: the unit's team rows read the union envelope, a team drill only its own
  sprints. The bar offers period, team/member, domain, domain view, activity type, work category and (with more than one) connection;
  `normalizeCostMatrixFilter` drops `breakdown`, `bucket`, `by`, `itemKind` and `epicId` off the request and the URL.
- **Home overview** (`pages/Home.tsx`, plan amendment A9; `utils/homeOverview.ts` is its pure logic). The landing page is
  the WHOLE unit at a glance — never the remembered team, the page description says so — as four tiles over UNIT-level
  report endpoints, **five requests and no aggregator** (`["home", <report>]` keys, staleTime 60 s; the budget is pinned by
  `Home.test.tsx`): the shared `["reports","filters"]` reference data (same key as the report pages, so opening a report reuses
  it; only its `timeZone` is read, nothing waits on it and its failure shows nowhere), `sprint-consistency?lastSprints=1` (each team's last closed sprint, all from one `fact_sprint` row:
  committed = initial, final, delivered; a lazy `HomeVelocityChart` beside a table with a Closed date read in the configured zone and the
  orange "Drift" badge + frozen figures like the report pages; title → Velocity, secondary links → Throughput and Sprint
  consistency), `cycle-time` (the server's default trailing 90 days: median/p90/finished count and `CycleTimeTrendChart` at
  `height={200}` with the trend as a visually hidden table), `aging-wip` (tasks in progress, and "Past p85" orange /
  "Past p95" red as the top two configured thresholds' counts — read off the SERVER's `band` through `bandTone`, epics
  not counted, dashes plus a reason when the thresholds are hidden, "N+" when the 500-item list was truncated) and
  `data-quality` (`qualityHighlights`: the configuration kinds — derive warnings, unmapped statuses/boards, domains
  without an owner — first, then the volume kinds by count, five shown as orange badges, a plural "and N more kind(s)",
  "All findings"; teal "None found" when clean). `HomeTile` is the shell: a section whose h3 title IS the link into
  the full report, a caption stating the period/scope, `aria-busy`, and its OWN skeleton/error triage (one failing report
  never takes another tile down); the page owns the ONE polite "Loading…" live region. **No link is ever bare** (a bare report
  URL applies the remembered team): every one carries `lastSprints=1` or a from/to period — the one the server resolved
  (`overviewPeriod`: `meta.from/to` of the cycle-time or data-quality answer), else the same trailing 90 days computed
  locally in the configured zone (UTC dates while the reference data is pending or failed). When every answer says `derivedAt: null` and none failed the page shows the empty state instead
  (admin: data sources + metrics settings links); a failed request keeps the grid so the empty state never hides it.
- **Load order**: `["reports","filters"]` (staleTime 60 s) → the page query keyed
  `["reports", <report>, <serialized filter>]`, `enabled` once the filters loaded,
  `placeholderData: keepPreviousData` (`ReportChartCard` dims the previous body and sets `aria-busy`).
  `ReportChartCard` owns the load/empty/error triage; the row NAME in `ReportGroupsTable` is a
  `RouterLink` narrowing `teamId` (UNIT) or `accountId` (TEAM) — "name is the way in".
- **Chart rules** (ported from Lettuce): charts live only in lazy chunks (the page lazy-imports its
  chart component), each chart component imports `@mantine/charts/styles.css` itself, a legend for
  two or more series (none for one), one axis (never dual), and every chart has its numbers in a
  table beside it (velocity and the sprint charts: the per-sprint table; throughput's period chart:
  `ThroughputBucketTable`). Colour is never the only carrier of identity — legend, tooltip and table are. Tests mock `@mantine/charts` (recharts draws nothing under happy-dom) and assert
  the props. **Colour vocabulary, no new hue** — concrete shades in `utils/chartColors.ts`: blue `flow.6` =
  plan/committed, teal `teal.8` = delivered, orange `orange.8` = carried over/added scope, red
  `red.7` = dropped/blocked, gray `gray.6` = removed/neutral; the final-scope blue is per scheme
  (`useComputedColorScheme`: `flow.8` light, `flow.4` dark). Every mark must clear WCAG 1.4.11
  (≥ 3:1) on white and the `#f5f7fb` canvas (light) and the `#2e2e2e` paper and `#1f1f1f` canvas
  (dark); `chartColors.test.ts` recomputes the ratios from the theme (the file's comment holds the
  table: e.g. flow.6 3.56 on white, teal.8 3.44 on dark paper, red.7 3.53, orange.8 3.79, gray.6 3.32
  on white; flow.8 is 2.70:1 on dark paper, hence `flow.4` there). Adjacent series in ONE chart must
  also differ enough to tell apart (the dataviz validator's ΔE ≥ 15 floor): never two shades of one
  hue side by side (velocity's initial/final pair is a known exception — legend, tooltip and table
  carry it), and stacked segments are ordered so orange and red never touch (sprint consistency:
  carried over · delivered · dropped, teal between). One question per chart: sprint consistency is
  three small charts (committed vs delivered; the stacked final = carried + delivered + dropped;
  added vs removed), not one fourteen-series chart. A report's own resolution param (`bucket`) is a
  managed filter param, serialized into the query key; a page that must label data by the request
  that produced it returns that request's param WITH the report (throughput's `bucket`), so a
  refetch over kept data never mislabels the old rows.
- **Dates are days in the configured zone**: `GET /reports/filters` returns `timeZone`
  (`metrics.settings.time_zone`), the zone the server reads `from`/`to` in; the bar's "today", presets
  and date-picker maximum come from `todayIsoDate(filters.timeZone)`, and dates render through
  `utils/formatDate.ts` (`YYYY-MM-DD`, never `toLocaleString`; `formatDate(ms, fallback, timeZone)`
  reads the calendar day in that zone — UTC only where no zone is passed);
  MD through `utils/reportFormat.ts`'s `formatMd` (≤ 2 decimals).

## Internationalization (i18n)

The SPA is **N-language by architecture** via react-i18next (`src/i18n.ts`); the shipped bundles
are English (THE default and fallback everywhere) and Polish. All user-facing strings go through
`const { t } = useTranslation()` / `<Trans>` — **no hardcoded UI text**. Conventions:

- **Resources** live in `src/locales/{en,pl}/<area>.json`, one file per area (`appShell`, `auth`,
  `changelog`, `common`, `dataSources`, `home`, `metrics`, `reports`, `teams`, `users`); `i18n.ts` merges them into a single
  `translation` namespace, so keys read `area.key` (e.g. `t("auth.signIn")`). Only EN is
  statically imported — its typed `en` tree is the key canon AND the runtime fallback; every other
  language is auto-discovered from `locales/<lang>/` via `import.meta.glob`. Bundles are eager on
  purpose; move non-EN to lazy loading when a 3rd language ships or a bundle grows large.
- **Keys are typed**: `src/i18next.d.ts` augments i18next's `CustomTypeOptions` with `typeof en`
  from `i18n.ts` (the `@public`-tagged export — knip can't trace the d.ts consumer), so every
  `t()` call and `i18nKey` is compile-checked against the EN tree — a typo or removed key fails
  `tsc`/`npm run build`. Fields holding a key are typed `ParseKeys` (from `i18next`), never
  `string` (the `NavLeaf` `label` pattern in `utils/navigation.ts`); functions taking a translator
  take `TFunction`, never a hand-written `(key: string) => string`.
- **`common.*` is the shared source**: actions, field labels, shared vocabulary. Reuse it instead
  of duplicating; build Mantine `Select` option labels from `t()` at render so they translate.
- **Keep key parity vs EN for every shipped language** — a shipped bundle is all-or-nothing: every
  English key must exist in each `locales/<lang>/` (language-specific plural variants are
  expected), enforced by `locales/parity.test.ts` (auto-discovers language folders; also pins
  folders == `SUPPORTED_LANGUAGES`). Use i18next interpolation (`{{name}}`) and plural keys rather
  than string concatenation.
- **Adding a language** = a complete `locales/<lang>/` folder (every area file, parity-gated) +
  one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line — the Record
  type and the parity test each fail loudly on a missed step. Server-side, the `LocalizedText(en, pl)`
  constructor arity is the parity gate for email texts (a new language means a new parameter —
  every email text becomes a compile error until translated), and `users/Languages.kt` mirrors
  `SUPPORTED_LANGUAGES`.
- **Polish voice convention** (inherited from Lettuce): inclusive slash forms, active/direct
  voice, same tense and meaning as the English; spell out irregular feminines in full. No
  impersonal/passive dodges.
- The language switcher is the Language section of `components/UserMenu.tsx` (the header account
  menu) — `Menu.Item`s of NATIVE language names (`NATIVE_LANGUAGE_NAMES`, deliberate constants:
  readable before switching, and `Intl.DisplayNames` yields lowercase forms); choice persists in
  `localStorage` (`flow.lang`) and updates `<html lang>` (the `languageChanged` hook in
  `i18n.ts`). **The user language is ONE synced property (V1's `language` column)**: the switcher
  also fire-and-forget saves it server-side (`setUserLanguage` — it drives every email sent to the
  user), and `persistSession` applies the login/refresh response's `language` to the UI — signing
  in restores the user's language on any device, and an admin change propagates at the ≤15-min
  silent refresh. Emails render in the RECIPIENT'S stored language (`LocalizedText(en, pl)` in
  `infra/mail/`, EN fallback).
- **Tests render English**: `src/test/setup.ts` imports `../i18n` and forces `en`, so text-based
  assertions match the EN resources.

## Theming

**The design language is theme-owned** (Lettuce's "clean enterprise SaaS" posture) —
`src/theme.ts` + `src/themeVariables.ts` + `src/theme.module.css` are the single source, and
`src/theme.test.ts` guards every contrast ratio:

- The brand is the 10-stop **`flow` blue tuple** with `primaryShade: { light: 8, dark: 9 }` —
  Mantine's own shade 7/8 pair falls just short of 4.5:1 for white text on this hue, so the
  primary shades sit one step deeper than a stock Mantine blue — and `autoContrast: true`.
  **Restrained** — in UI chrome and status badges (chart series colours are
  `utils/chartColors.ts`'s): blue marks only primary CTAs, the active nav item (a light tint + a
  3px accent bar), focus and the in-progress status (see the colour vocabulary). Everything else
  is neutral BY THEME DEFAULT — `Anchor` is text-coloured with a
  hover underline, `Chip` is `variant="light"`, `Badge` is `variant="light"`, `ActionIcon` is
  `variant="subtle" color="gray"` (destructive ones pass `color="red"`), `Menu` is bottom-end in a
  portal, tables are `verticalSpacing="xs"` (≈40px rows). Pages never pass those props back. Teal
  stays the SUCCESS colour, red the BLOCKING one, orange the soft-finding one (drift, anomalies,
  data-quality findings); the ADMIN role badge is
  `variant="outline" color="gray"`. **Never reintroduce stock-green success states** (blue itself
  IS the brand now, so unlike a violet-branded sibling it is never forbidden as "just another
  action").
- **Colour tokens are AA-tested** (`themeVariables.ts`, ported from Lettuce): `LIGHT_TOKENS`/
  `DARK_TOKENS` (text, dimmed, canvas, surface tint, borders, error inks) and
  `LIGHT_VARIANT_INKS` (the per-hue ink over a `variant="light"` surface — Mantine's stock light-
  scheme ink fails 4.5:1 for several hues) go in through `cssVariablesResolver` in `main.tsx`.
  Never hand a shade-suffixed colour (`"orange.8"`) to a light Badge/Alert — the tint comes from
  the hue, the ink from this map. **`--flow-accent-ink`** is the brand hue where it must be
  PERCEIVED against the canvas (the nav marker, the anchor hover underline, focus): the light
  primary in light mode, the 4-shade in dark mode. `index.css` repeats the two canvas hexes for
  the first paint (`public/color-scheme.js` stamps the scheme on `<html>` before the bundle
  loads); the theme test pins them equal to the tokens.
- `theme.module.css` is scheme-aware **via `light-dark()`** throughout (tables card-framed on a
  quiet tinted canvas, white header/navbar surfaces, hairline borders) — new surface styling
  follows that pattern, not `[data-mantine-color-scheme]` selectors. Every `Table` inherits the
  card frame + hoverable rows + neutral compact header from the theme's `Table.extend` — don't add
  per-table frames.
- Soft diffuse `shadows` scale; tightened heading sizes (pages title themselves with `order={2}`).
- Inter is bundled (`@fontsource-variable/inter`, imported in `main.tsx`) so it loads same-origin
  and satisfies the CSP `font-src 'self'`; the system stack is the fallback.
- **Overlay accessibility rules (pinned by the e2e axe sweep over dialogs):** every `Modal` and
  `Drawer` passes `closeButtonProps={{ "aria-label": t("common.action.close") }}` — Mantine's
  close X has no default name — or hides the X with `withCloseButton={false}` when a footer
  button is the one deliberate exit (`OneTimePasswordModal`).
- The logo SVGs (`public/logo-*.svg` + `favicon.svg` — "Rolling": one tapering stream running round a
  blue disc and rolling inward into a curl, dark variant inverted to navy on `flow.4`; rendered by
  `components/BrandLogo.tsx`) are the brand mark. The path is computed geometry (a tapered ribbon along
  a circle-then-spiral spine, ~120 points) — change it in a vector editor, not by hand-editing the
  path data. Restyle rule: keep aria-labels, roles, and
  real semantic elements stable — e2e and unit tests locate by role/name.

## Changelog & app versioning

The user-facing changelog is a **build-time artifact** — no DB, no API, changes only with a
deploy. `src/changelog/entries.ts` holds `ChangelogEntry` rows (`version`, `date` `YYYY-MM-DD`,
`en`/`pl` **markdown** bodies), newest first; the app's only human-readable version is
`APP_VERSION` in **`src/changelog/version.ts`** — its own tiny module so the shell's eager imports
(VersionStamp, the what's-new dot) never pull the bilingual entries into the main bundle (the
entries ride the lazy Changelog chunk only). **A release = the new entry at the top of
entries.ts + the bump of that one literal** (the Gradle `1.0.0-SNAPSHOT` is unrelated);
`entries.test.ts` pins `CHANGELOG[0].version === APP_VERSION`, so forgetting either half fails the
suite. Release convention:

- Write both language bodies by hand (or LLM) — never derive them from commit messages. Bodies
  are *content*, so they live in the data file, not `locales/`; the PL body follows the Polish
  voice conventions (inclusive slash forms, active voice).
- Keep dates descending (same-day releases are fine — newest stays on top), versions unique, and
  both bodies non-empty — pinned by `src/changelog/entries.test.ts`.
- Complete publication with the annotated version tag and bilingual GitHub release described in
  [the application release process](../.claude/docs/app-releases.md). Documentation/test-only
  changes may retain the version; published tags never move.
- Keep phrases that tests assert on in plain text runs: markdown formatting splits text nodes,
  and testing-library's `getByText` matches direct text nodes only.

Rendering & UI wiring:

- `pages/Changelog.tsx` (`/changelog`, authenticated, lazy) renders the entries as a Mantine
  `Timeline` with `MarkdownView` bodies in the viewer's language, and calls `markChangelogSeen()`
  on mount. **Bodies are authored EN+PL only, by policy** — a new shipped UI language does NOT
  add changelog fields; non-authored languages read the English body (`AUTHORED_LANGUAGES` in the
  page). `components/MarkdownView.tsx` (Typography + react-markdown + remark-gfm/gemoji, all
  lazy-chunk-only deps) is the one markdown renderer — reuse it for future markdown content.
- `components/VersionStamp.tsx` shows `v<APP_VERSION> · <sha> · <time>`; its optional `to` prop
  renders it as a router link — the navbar footer instance links to `/changelog` (no dot on it any
  more), the Login/AuthCard instance deliberately stays plain (the route is behind auth). The
  "Changelog" entry is an `ACCOUNT_NAV` leaf rendered by the `UserMenu` and the command palette,
  not a sidebar item.
- The **"what's new" dot** is a red Mantine `Indicator` around the header `UserMenu` trigger (it
  carries the `title="What's new"` e2e locates; the Changelog menu item shows a text badge
  instead of a second title, which would trip strict-mode locators while the menu is open), shown
  while localStorage `flow.changelog` (`{seenVersion}`) differs from `APP_VERSION`. It's driven
  by `hooks/useChangelogSeen.ts` via `useSyncExternalStore` over a module-level listener set — do
  **not** replace that with a plain localStorage read in state: the shell renders before the
  page's mark-seen effect, so the dot would only clear on the next navigation. The seen-state is
  device-level (survives logout, like `flow.lang`) and hand-rolls its guarded read/write, not
  `useStoredState` (that hook owns the `flow.viewSettings.*` namespace).
- Tests: `pages/Changelog.test.tsx`, `changelog/entries.test.ts`, the dot/nav cases in
  `App.test.tsx`, the link/prefix cases in `components/VersionStamp.test.tsx`.

## Not yet ported from Toadie / Lettuce (port, don't reinvent)

When a feature needs one of these, port the sibling's `web/` implementation and its
`web/CLAUDE.md` section wholesale:

- **Lettuce's** `safeBackParam` open-redirect guard for any future `?back=` param,
  `StatusPill`/`MetaStrip`/`FormFooter`/`ListToolbar` (`lettuce/web/src/components/`), and
  `useDiscardGuard` for dirty-form navigation.
- **Covenant's catalog surface** (`covenant/web/src/`) — the filter-set/facets pattern
  (`hooks/useContractFilterState.ts` + `components/ContractFilterControls.tsx`), the CodeMirror
  document editor, Save-anyway, and the reader/render-model views — port these only once the Jira
  domain model needs a document- or finding-shaped list; today's Teams/Users lists are the
  `useRegistryListControls`/`Users.tsx` templates above, which is as far as the foundation goes.
