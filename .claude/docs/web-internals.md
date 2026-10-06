# Web internals (read on demand)

Mechanism and narrative text moved verbatim out of `web/CLAUDE.md` (B7 step 4) so that file stays a lean always-loaded-in-`web/` rule set. The binding rules (every never/must/always) stay in `web/CLAUDE.md` as one-liners; this doc holds the mechanics behind them. **Read the relevant section before changing the transport/session layer, the user/feature-flag pages, the language switcher, the colour tokens or logo, or the changelog wiring.**

### `http.ts` — the transport's refresh internals

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

### `session.ts`

- **`session.ts`**: token/roles/userId storage under the `flow.auth.*` localStorage keys, written
  by `persistSession(LoginResponse)` on login/MFA and conditionally by `persistRefreshedSession`
  on refresh, and the render-time accessors (`isAdmin()`, `getUserId()`, …). Components whose
  controls or queries depend on ADMIN use `useAdmin()` from `auth.tsx`; it subscribes to role
  changes from silent refresh and other tabs. A random stored identity distinguishes sign-ins,
  while credential snapshots guard refresh publication; renewing tokens preserves the logical
  session so staggered 401s can reuse the new token.

### Session boundary

- **Session boundary** (`components/SessionBoundary.tsx` + `utils/sessionQueryCache.ts`): bind the
  root `QueryClient` to logical session identity changes, and key the mounted app subtree by that
  identity. A different sign-in, definitive automatic sign-out, explicit sign-out, or a change
  from another tab must synchronously cancel and remove caller-scoped query data and discard
  mounted form/mutation results before the next account renders; an access/refresh token rotation
  within the same identity must retain them. Keep this at the session boundary rather than
  clearing individual page keys, because user/team/account queries all contain caller-specific
  projections. Late requests from the previous identity must not repopulate the new account's UI
  or cache.

### Sign-out and the return destination

Explicit sign-out opens `/login` without a saved return destination, so the next sign-in opens
Home even when a different account signs in. Anonymous access to a protected link still preserves
its path, query and hash. Test logout navigation with the real route guards from a non-home page;
a root-only menu test cannot detect a stale return destination. Reading the signed-out notice
during render must be side-effect free. Acknowledge it only after the login page commits, so a
suspended or retried render cannot discard the notice.

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

### Adding a language

- **Adding a language** = a complete `locales/<lang>/` folder (every area file, parity-gated) +
  one code in `SUPPORTED_LANGUAGES` (`i18n.ts`) + one `NATIVE_LANGUAGE_NAMES` line — the Record
  type and the parity test each fail loudly on a missed step. Server-side, the `LocalizedText(en, pl)`
  constructor arity is the parity gate for email texts (a new language means a new parameter —
  every email text becomes a compile error until translated), and `users/Languages.kt` mirrors
  `SUPPORTED_LANGUAGES`.

### The language switcher and the synced user language

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

### Colour tokens

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

### The logo

- The logo SVGs (`public/logo-*.svg` + `favicon.svg` — "Rolling": one tapering stream running round a
  blue disc and rolling inward into a curl, dark variant inverted to navy on `flow.4`; rendered by
  `components/BrandLogo.tsx`) are the brand mark. The path is computed geometry (a tapered ribbon along
  a circle-then-spiral spine, ~120 points) — change it in a vector editor, not by hand-editing the
  path data. Restyle rule: keep aria-labels, roles, and
  real semantic elements stable — e2e and unit tests locate by role/name.

### Changelog rendering & UI wiring

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
