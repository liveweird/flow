# E2E (Playwright, blackbox)

Browser end-to-end tests that treat the app as a **blackbox**: they drive a real Chromium against
the whole stack (SPA + server + Flyway + Postgres) served single-origin at `http://localhost:8084`
by `docker compose`. This package is fully isolated from `web/` — it imports none of the app source
and only speaks HTTP/DOM.

## Run

```bash
cd e2e
npm install
npm run install:browsers      # one-time: download Chromium
npm test                      # reuses or starts the stack, runs specs, leaves the stack intact
```

- `global-setup.ts` starts `docker compose up -d --build` and waits for `:8084` — **unless a stack
  is already running there**, which it reuses (fast local iteration: keep `docker compose up` or a
  local `WEB_STATIC_DIR=… ./gradlew :server:run` going and just run `npm test`).
  Setup never tears down services or volumes, including after a failed start or test run. Tests
  clean up only the records they own; existing demo data stays intact.
- Docker is needed only when starting the default stack. `E2E_BASE_URL` can target an already
  running stack; an unavailable custom URL fails without starting the default Compose project.
  (8084 — see `docker-compose.yaml`.)

Docker-free gates ride every spec change — run them before merging, like the web
package's lint/knip:

```bash
npm run typecheck             # tsc --noEmit — Playwright only TRANSPILES TS, it never checks it
npm run check:scenarios       # spec ↔ scenario parity: files exist, test() titles == headings
npm run test:setup            # stack reuse/start/failure paths preserve services and data
```

`check:scenarios` enforces the same-commit rule below mechanically (both directions, orphan
files included); `accessibility.spec.ts` is its one registered skip — the parameterized-title
carve-out in [`scenarios/README.md`](scenarios/README.md).

## Parallel execution

The suite runs on **4 workers by default** (`E2E_WORKERS` overrides; `E2E_WORKERS=1` restores
fully-serial behavior). The serial unit is the **spec file** (`fullyParallel: false` — a file's
tests may be order-dependent); different files run concurrently. That is only sound because
**every spec file owns its server-side state exclusively** — the standing rulebook, inherited
from Lettuce, that any new or edited spec must satisfy:

- Each spec's scenario file declares its **Owns** line (exclusive server-side state; "nothing —
  read-only" when applicable). Today: `auth`, `changelog` and `shell` (device-local
  localStorage only) are read-only; `accessibility` owns one API-seeded fixture team
  (`e2e-axe-team-*`); `users` owns its throwaway accounts; `teams` owns its throwaway teams
  (unique `e2e-team-*` names) and users; `i18n` owns its throwaway user (and ONLY that user's
  language — **seeded accounts must stay English**: every login applies the stored language to
  that session's UI, so a Polish seed admin would flip parallel specs mid-run); `password-reset`
  owns its throwaway account (its reset requests use unique per-run emails against the
  in-memory per-email throttle, and both tests together stay under the per-IP 5/min reset
  bucket); `mfa` owns its throwaway accounts and toggles ONLY their MFA flags (the seed admin's
  MFA flag is never touched — enabling it would make every spec's login demand a code);
  `list-mutation-refresh` owns its own uniquely named users/teams, created and deleted through
  the real API — all deleted by their own spec; `data-sources` owns its own uniquely named
  connection (`e2e-jira-*`) and a throwaway user — the synced connection's `raw.*`/`norm.*` rows
  are left in place (purged only after the grace period, see its scenario file), a deliberate
  exception to "each spec cleans up its own state" that costs no cross-spec interference since no
  other spec reads Jira raw/normalized rows; `metrics-config` owns its own uniquely named
  connections (`e2e-metrics-ds-*`, one per test rather than shared — see its scenario file),
  teams (`e2e-metrics-team-*`) and a throwaway user, PLUS the GLOBAL `metrics.settings` singleton
  — captured through the API before its edits and restored through the API afterwards, the one
  spec in the suite that touches shared non-append-only state, since no other spec reads or
  writes it; `reports` owns its own synced Jira-stub connection (`e2e-reports-ds-*`), team
  (`e2e-reports-team-*`, the stub's FLO board mapped to it, one stub Jira person on its roster) and
  throwaway regular user, all created and deleted through the API (the roster membership before the
  team: a person belongs to one team at a time, globally, and a deleted team keeps its rows) — and
  every report it reads is narrowed to ITS team or, unit-level, to ITS connection (`connectionId` in
  the URL), since an unnarrowed UNIT-level report would also count the other specs' synced
  connections.
- **The Teams registry is shared, append-only state.** Several specs create teams concurrently
  (`teams`, `accessibility`, `list-mutation-refresh`), so a spec only ever appends and removes its
  OWN uniquely named `e2e-*` rows, never edits or deletes another's or a shared seed — and every
  list assertion is anchored on a name filter, never on an unfiltered total or row count
  (Toadie's dictionary and registry rulebook, applied verbatim).
- Seeded accounts are never mutated. The seed admin (`admin@flow.local`) is a shared
  read-mostly actor: specs sign in as it but must not change its password, roles, or state — a
  future spec that needs a mutated account creates a throwaway.
- E2e-created entities carry a sweepable marker — every e2e-created entity's name and
  every e2e-created user's email contains `e2e`, and nothing that must SURVIVE runs is ever
  named that way. Each spec deletes its own state, so the rule is currently satisfied by
  self-cleanup; port Lettuce's `sweep-residue` global-setup pass if aborted runs start leaving
  residue that self-cleanup misses.
- Artifacts must be unique-named and list asserts filter- or sort-anchored — never bare page-1
  assumptions. A future spec minting globally-visible state (banners, org-wide notifications)
  runs in its own dependent project phase, not in the parallel pool.

## What's covered

**Each spec's full design lives in its scenario file under [`scenarios/`](scenarios/README.md)** —
versioned natural-language test-design artifacts (actors, owned state, numbered steps, expected
outcomes). **A new or behaviorally changed test lands with its scenario file and its line below in
the same commit** — this list is the coverage map, the scenario file is the design.

- [`accessibility.spec.ts`](scenarios/accessibility.md) — axe WCAG A/AA smoke: login + the
  authenticated list/form pages (`/`, `/teams`, `/users`, `/users/new`, `/feature-flags`,
  `/data-sources`, `/metrics-settings`, `/reports/{velocity,wip,epic-progress,data-quality,cost-matrix}`,
  `/change-password`, `/changelog`), the detail pages of an API-seeded fixture
  team (its roster page, the admin's own edit-user and user-features pages), `/reset-password`,
  the not-found page, and a registry editor modal scoped to its dialog; `color-contrast` included
  (the theme's tokens are AA-tested in `web/src/theme.test.ts`).
- [`auth.spec.ts`](scenarios/auth.md) — login / logout / invalid credentials / guarded deep link with query and hash;
  explicit sign-out from a non-home protected page returns the next sign-in to Home,
  including while server revocation is delayed.
- [`shell.spec.ts`](scenarios/shell.md) — the app shell chrome: the icon rail collapses/expands
  the navbar and remembers the choice across reload; the account menu's theme control switches
  the color scheme and persists it.
- [`changelog.spec.ts`](scenarios/changelog.md) — the what's-new dot on a fresh device
  leads to the changelog via the version stamp and clears once read (no language switching
  — it runs as the seed admin; see `i18n.spec.ts`).
- [`list-mutation-refresh.spec.ts`](scenarios/list-mutation-refresh.md) — held first
  filter-response for Users or Teams cannot restore a deleted row; a held Feature Flags
  response cannot undo a successful toggle.
- [`i18n.spec.ts`](scenarios/i18n.md) — the synced per-user language: a throwaway user
  switches to Polish, the choice survives a reload AND a wiped-device re-login (served from
  the stored value), and the admin's English flips it back; seeded accounts stay English.
- [`mfa.spec.ts`](scenarios/mfa.md) — email MFA + the flags surfaces: the /feature-flags
  row switch and per-user editor round-trip a throwaway user's MFA flag; an MFA-enabled
  account signs in using the fully revealed generated password and the emailed 6-digit code
  via Mailpit (skips itself without it).
- [`password-reset.spec.ts`](scenarios/password-reset.md) — the forgot-password flow:
  neutral confirmation + per-email throttle for unknown addresses; the full email roundtrip
  through the compose stack's Mailpit (new password works, old one is dead — skips itself
  without Mailpit).
- [`data-sources.spec.ts`](scenarios/data-sources.md) — the v0.2.0 Jira ingestion admin surface:
  create a connection against the Jira stub → test connection → sync it end to end (polling until
  the job succeeds and the raw-store counts show the full 1,200-issue in-scope dataset) → read the
  data profile and the raw issue inspector (a known key, then a malformed one) → delete from the
  list; a regular user sees no Data sources nav link and is bounced from the URL.
- [`metrics-config.spec.ts`](scenarios/metrics-config.md) — the v0.3.0 metrics CONFIGURATION
  surfaces (the reports are `reports.spec.ts`): the global Metrics settings form (a save
  persists, an all-weekend-days value is refused inline) restored to its pre-test values through
  the API; a synced Jira-stub connection's per-connection Metrics configuration (preselected
  stages, a board → team mapping, a second board mapped to the same team marked `409`); D1's
  dated Jira-user team membership on a throwaway team (an overlapping second membership refused
  inline) and a regular user's read-only view of it, with no Metrics settings nav access.
- [`reports.spec.ts`](scenarios/reports.md) — the v0.3.0 reports, read by a NON-ADMIN user (D12)
  over an API-seeded, synced-and-derived Jira-stub connection with the FLO board mapped to a
  throwaway team (`helpers.ts`'s `syncStubDataSourceViaApi`/`configureMetricsViaApi`/
  `awaitDerivedSprint`/`awaitDerivedTeamCost`, plus one API-seeded stub person on the team's roster,
  removed before the team): the golden FLO sprint's figures in Velocity, Throughput (regrouped by
  month — URL and table follow) and Sprint consistency; Cycle time and Task estimation accuracy
  show their distributions (or the minimum-sample notice) with their accounting. Batch 2 (M5): the
  last three sprints and the team → member drill on Velocity; WIP by stage/status (URL and table
  follow), Aging WIP and Blocked time; Epic progress drilled unit → FLO domain → the golden epic
  (`golden.epic`'s budget and dates, the daily table); Data quality (tiles equal their cards, a
  regular user gets no admin config link); the Cost matrix (totals add up, drill to a team); the Home
  overview's four tiles and a tile link landing on its report with the period in the URL; and an axe
  scan of the populated WIP, Epic progress, Cost matrix, Home and Data quality pages. Period-bound reads use a fixed window around the
  stub's reference date and narrow to the spec's own connection through the URL.
- [`teams.spec.ts`](scenarios/teams.md) — the flat-teams registry: create through the modal →
  add a member from the searchable picker → rename → remove the member → delete from the list;
  a regular user's read-only list and roster (no New team, no row menu, no picker).
- [`users.spec.ts`](scenarios/users.md) — the account lifecycle: create via the one-time
  password reveal → the new user's limited view + self password change → promotion →
  deletion → the dead login; own-row protections on the admin's row.

Specs log in with the seeded admin (`admin@flow.local`, password `changeme`), and use unique
content where they create any — so they don't depend on a clean database or absolute counts.

### Logging in

`createUserViaUi()` waits for the one-time password reveal to render before reading the
password. A click on Show password alone is not evidence that the mask has been replaced.
Every journey that creates a user through this shared helper inherits that synchronization.

`helpers.login()` drives the **real login form** (clearing any leftover `flow.auth.*`
localStorage session first — while one exists, `RedirectIfAuthed` bounces `/login` away and the
form never renders). Development stacks lift the per-IP login bucket
(`security.rateLimit.loginPerMinute`, 1000/min in development vs 10/min in production), so
form-driven logins aren't throttled. When the suite grows enough that per-spec form logins
dominate runtime, port Lettuce's API-minted-session fast path (write the `flow.auth.*` keys the
SPA itself persists, falling back to the real form) — keep specs whose subject *is* a credential
on the real form driver.

## Deliberately not covered

- **Login lockout (429)** — five failed logins would lock the seed admin for 15 minutes in the
  shared database and poison the rest of the run. Covered by `LoginThrottleTest` /
  `LoginLockoutTest` (server).
- **Token refresh / expiry (clock-driven)** — real expiry needs clock control; covered by server
  tests (`RefreshTest`) and the `web/src/api/api.test.ts` unit tests. (Lettuce additionally
  covers the browser-side refresh behavior via `page.route` fault injection — port
  `error-handling.spec.ts` when the SPA grows surfaces worth failing.)
- **The authz matrix** — covered by the server tests (`GuardsTest`, `AnonymousAccessTest`); E2E
  asserts only user-visible consequences.
- **Dark-mode rendering** — the palette is theme-owned (`web/src/theme.ts`); `shell.spec.ts`
  asserts the color-scheme attribute switches and persists, but no e2e asserts rendered colors,
  and there is no visual-regression suite.
- **Responsive / cross-browser / visual automation** — the suite deliberately runs a single
  **Desktop Chrome (chromium) project** only, with no mobile project or screenshot comparison;
  layout relies on Mantine semantics plus the role/label-based locators every spec uses.
  Accessibility gets the `accessibility.spec.ts` axe smoke.

Reports/artifacts land in `playwright-report/` and `test-results/` (git-ignored).
