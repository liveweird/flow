# Frontend (`web/`)

Vite + React 19 + TypeScript SPA: the shell + auth, user/feature management, MFA, password reset,
the changelog, and the flat-teams registry — v0.1.0's foundation — plus, since v0.2.0, the Data
sources registry (ADMIN-managed Jira Cloud connections). Ingested Jira data itself (details/sync
jobs/profile pages, dashboards) lands in later v0.2.0 commits — `pages/Home.tsx` still states
plainly that there is none to show yet. Routes are lazy. New capability that Covenant, Toadie or
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
  labelled, always-open sections (currently Overview and Administration) of `NavLeaf`s (the
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
  Alerts — never as toasts.
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
  destructive confirm buttons, a failed row), orange = a soft finding a later checks pipeline will
  save through a waiver, teal = success, gray = neutral state. A new status colour goes through
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
description, with `utils/charCount.ts`'s "123 / 4000" counter) and `RegistryEditorActions`
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

## Data sources (`pages/DataSources.tsx`, `components/DataSourceEditorModal.tsx`)

The first Jira-domain surface (v0.2.0 plan §9/§10), ADMIN-only end to end: the nav leaf
(`IconPlugConnected`, `adminOnly`), the route (`/data-sources`, under the same `RequireAdmin`
group as Users/Feature flags in `App.tsx`), and every endpoint (`requireAdmin` server-side).

- **`api/dataSources.ts`** mirrors `api/teams.ts`'s shape — list/create/update/delete plus the two
  Test-connection wrappers (`testDataSourceAdHoc`/`testDataSourceStored`) and `requestSyncJob`
  (`POST …/sync-jobs`, the sync-jobs history/status wrappers arrive with the details page, plan
  commit 11). Unlike Teams, `DataSourcePage.items` already carries the FULL `DataSourceResponse`
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
  `tenant_info` succeeds — reused as-is by the future details page.
- **`utils/dataSourceLinks.ts`** holds just `dataSourcesPath` today; the per-id `dataSourcePath`
  helper arrives with the details page (plan commit 11) rather than sitting unused.
- `pages/Home.tsx`'s admin empty state links to `/data-sources` (a plain `Anchor` under the
  `EmptyState`, not a rewrite of that shared component) — "keep it simple" per the commit plan.

## Internationalization (i18n)

The SPA is **N-language by architecture** via react-i18next (`src/i18n.ts`); the shipped bundles
are English (THE default and fallback everywhere) and Polish. All user-facing strings go through
`const { t } = useTranslation()` / `<Trans>` — **no hardcoded UI text**. Conventions:

- **Resources** live in `src/locales/{en,pl}/<area>.json`, one file per area (`appShell`, `auth`,
  `changelog`, `common`, `dataSources`, `home`, `teams`, `users`); `i18n.ts` merges them into a single
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
  **Restrained**: blue marks only primary CTAs, the active nav item (a light tint + a 3px accent
  bar) and focus. Everything else is neutral BY THEME DEFAULT — `Anchor` is text-coloured with a
  hover underline, `Chip` is `variant="light"`, `Badge` is `variant="light"`, `ActionIcon` is
  `variant="subtle" color="gray"` (destructive ones pass `color="red"`), `Menu` is bottom-end in a
  portal, tables are `verticalSpacing="xs"` (≈40px rows). Pages never pass those props back. Teal
  stays the SUCCESS colour, red the BLOCKING one, orange the WAIVED-finding one (reserved for the
  checks pipeline the Jira domain model will add); the ADMIN role badge is
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
- The logo SVGs (`public/logo-*.svg` — three curling streamlines meeting on a blue tile, rendered
  by `components/BrandLogo.tsx`) are the brand mark. Restyle rule: keep aria-labels, roles, and
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
- **Lettuce's** charts (`@mantine/charts` + `recharts`) for the flow-metrics dashboards to come.
