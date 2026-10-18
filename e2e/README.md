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
  The nightly `e2e.yml` builds the image and starts the stack in their own workflow steps first (so the
  time is attributable per step, `.claude/docs/build-times.md`), and setup then just reuses it.
  A stack the setup starts itself gets `INGEST_DERIVE_QUIET_SECONDS=1` (the nightly sets it in `e2e.yml`; start a stack by hand the
  same way): every configuration edit's DERIVE otherwise waits out the 45 s debounce before the specs can see it.
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
files included); `accessibility.spec.ts` and `accessibility-data.spec.ts` are its registered skips — the parameterized-title
carve-out in [`scenarios/README.md`](scenarios/README.md).

## Parallel execution

The suite runs on **4 workers by default** (`E2E_WORKERS` overrides; the `e2e.yml` `workflow_dispatch` form has a `workers` input for A/B runs; `E2E_WORKERS=1` restores
fully-serial behavior). The serial unit is the **spec file** (`fullyParallel: false` — a file's
tests may be order-dependent); different files run concurrently. That is only sound because
**every spec file owns its server-side state exclusively** — the standing rulebook, inherited
from Lettuce, that any new or edited spec must satisfy:

- Each spec's scenario file declares its **Owns** line (exclusive server-side state; "nothing —
  read-only" when applicable). Today: `auth`, `changelog` and `shell` (device-local
  localStorage only) are read-only; `accessibility` owns one API-seeded fixture team
  (`e2e-axe-team-*`); `accessibility-data` owns one synced Jira-stub connection (`e2e-axe-ds-*`,
  paused once derived) with the team its FLO board is mapped to (`e2e-axe-data-team-*`), both deleted
  through the API; `users` owns its
  throwaway accounts; `teams` owns its throwaway teams (unique `e2e-team-*` names) and users; `i18n` owns its throwaway user (and ONLY that user's
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
  connections; `reports-drill` owns its own synced Jira-stub connection (`e2e-drill-ds-*`), team (`e2e-drill-team-*`, no
  roster) and throwaway regular user, created and deleted through the API, and narrows every report it opens to its team and connection;
  `public-holidays` owns its own synced Jira-stub connection (`e2e-holidays-ds-*`), team
  (`e2e-holidays-team-*`, one stub person on its roster) and throwaway user, plus ONE `E2E Holiday-*` row of the GLOBAL
  public-holiday dictionary at a time (added and deleted inside its one test, swept by name), on a non-golden sprint so the
  concurrent `capacity` spec's golden window is untouched.
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
  `/data-sources`, `/metrics-settings`, `/capacity` (the pickers' empty state), `/public-holidays`, all seventeen `/reports/*` routes (the Deep dive without a selection, so its explainer) — each scanned once settled —
  `/change-password`, `/changelog`), the detail pages of an API-seeded fixture team (its team page,
  the admin's own edit-user and user-features pages), `/reset-password`, the not-found page, and a
  registry editor modal scoped to its dialog; the app shell at a mobile width (390x844, the burger present), light and dark; the login screen and the list/form/report pages again in
  the DARK scheme (`colorScheme: "dark"`, which the `auto` default follows); `color-contrast` included
  (the theme's tokens are AA-tested in `web/src/theme.test.ts`).
- [`accessibility-data.spec.ts`](scenarios/accessibility-data.md) — the same axe scan over its own
  API-seeded, synced-and-derived (then paused) Jira-stub connection: every report with derived data
  behind it plus the data-source details, data profile, raw issue inspector and metrics-configuration
  pages, in the light and the dark scheme, and the Deep dive with the golden sprint selected (its matrix drawn, and its Burn-up view).
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
  the job succeeds and the raw-store counts show the full 1,201-issue in-scope dataset) → read the
  data profile and the raw issue inspector (a known key, then a malformed one) → delete from the
  list; a regular user sees no Data sources nav link and is bounced from the URL.
- [`metrics-config.spec.ts`](scenarios/metrics-config.md) — the v0.3.0 metrics CONFIGURATION
  surfaces (the reports are `reports.spec.ts`): the global Metrics settings form (a save
  persists, an all-weekend-days value is refused inline) restored to its pre-test values through
  the API; a synced Jira-stub connection's per-connection Metrics configuration (preselected
  stages, a per-domain stage override saved with the rest and still there after a reload, a board → team mapping, a second board mapped to the same team marked `409`); D1's
  dated Jira-user team membership on a throwaway team (an overlapping second membership refused
  inline) and a regular user's read-only view of it, with no Metrics settings nav access; and seeding a throwaway team's Jira
  roster from the stub's Atlassian Team field (pick the Payments team in the "Seed from Jira Team field" dialog, tick one assignee,
  apply — the roster shows them as a current member from their earliest issue's day; the seeded row is removed before the team).
- [`reports.spec.ts`](scenarios/reports.md) — the v0.3.0 reports, read by a NON-ADMIN user (D12)
  over an API-seeded, synced-and-derived Jira-stub connection with the FLO board mapped to a
  throwaway team (`helpers.ts`'s `syncStubDataSourceViaApi`/`configureMetricsViaApi`/
  `awaitDerivedSprint`/`awaitDerivedTeamCost`, plus one API-seeded stub person on the team's roster,
  removed before the team): the golden FLO sprint's figures in Commitments (a team is always selected; its table has the Capacity and Load columns; the old `/reports/velocity` address redirects to it with its filters), Throughput (a team is always selected; regrouped by
  month — URL and table follow; switched to the cumulative view — URL param, running-total columns, dropped on the next tab) and Sprint consistency (a team is always selected and there is no Member control; sliced by Activity type = Bug the figures change, the live-figures note shows and there is no drift badge, and clearing it brings the golden figures back); Cycle time (a required team with no "All teams", plus its Epics card over a
  year-long window, kept without an owner table at team level, and a one-person pick: the URL account and the one-row Totals per person; its histograms' long-tail control — 5% default, 25%, 0, and back, with the URL param and the open "≥ X" row) and Task estimation accuracy
  (a required team with no "All teams", a one-person pick with the one-row Totals per person; each view shows its median/mean/items strip with no p90/p95 and an Accuracy measures table, or the minimum-sample notice, with its accounting); Epic estimation
  accuracy (a required team with no "All teams" and no Member control; the empty window, both views — each a median/mean/items strip with no p90/p95 and an Accuracy measures table, or the minimum-sample notice — the golden epic's ratio against its OWN estimate, then
  the domain), Estimate adjustments (a required team with no "All teams", tasks and epics blocks with their "Not charted: N unchanged" line, the activity-type filter,
  a one-person pick: the URL account and the one-row Totals per person), Reported time beside flow efficiency (reached through the Estimation tab with the spec's own team; domain and
  domain-view controls, no "All teams" on the required team, a one-person pick: the URL account, the "1. <name>" histogram column — or the min-sample note when the stub sample is too small — and the one-row Totals per person) and the Estimated backlog (a team's tiles and trend, the unit's
  pace note, a domain's empty pace, a team replacing the domain; no Member control and a pasted member dropped from the URL; the staleness slider — No limit, then 7 days, items plus hidden equal the unlimited count). Batch 2 (M5): the
  last three sprints and the team → member drill on Commitments; WIP by stage/status (URL and table
  follow; a required team with no "All teams" — WIP is read for one team or one domain) and one person's WIP inside the team (the URL account, a USER-level answer with a non-empty series and a one-entry `byUser`, no per-person view for one person), Aging WIP (its thresholds and open-work table) with its aging chart (the card, the "Workflow position" and "Age (working days)" axes, the
  seeded team's board columns on the x axis and one dot per task, statuses without a team; the page-level Tasks | Epics switch narrows the table to the chosen kind and puts `kind=epics` in the URL) and Blocked time; Epic progress drilled unit → FLO domain → the golden epic
  (`golden.epic`'s budget and dates, the daily table) and the FLO domain's period view (opens on "This period" with no `basis` in the URL, the weekly SPI/CPI trend chart and its
  table, then "Since start" in the URL); Data quality (tiles equal their cards, a
  regular user gets no admin config link); the Cost matrix (totals add up, the activity-type and work-category donuts' tables add up and a figure opens the tasks behind its slice, drill to a team, the capacity column for the team's manager only, the week split inside a scroller that never widens the page); the Home
  overview's four tiles and a tile link landing on its report with the period in the URL; and an axe
  scan of the populated WIP, Epic progress, Cost matrix, Home and Data quality pages; and the Deep dive (report 17): pick the golden
  sprint's domain and sprint (the options carry their dates, the connection follows from the pick; Show folds the Selection to a one-line summary and an **Edit selection** toggle), read the matrix's plan, approximate EV and cost layers, drill an epic into its tasks and a month into weeks (focus on the first
  week), open a cell's tooltip by keyboard focus, switch a layer off, the Burn-up tab (URL, chart legend, the daily table's figures) and back, then, after Edit selection, an epic's planned window in Epics mode — with the computed
  styles the unit tests cannot see (scroll margins, halos, the tooltip's height cap). Period-bound reads use a fixed window around the
  stub's reference date and narrow to the spec's own connection through the URL. Every report's filter bar is collapsible
  (folded by default; scenarios unfold it with `openFilters`), and one scenario folds and unfolds it: the toggle's
  `aria-expanded` and active-filter badge (on Commitments the period counts, the always-present team does not), the choice remembered per report across a reload. Another scenario covers the per-screen filter memory (client-side): a period and a person set on Commitments come back, with the very same URL, when the user returns through the nav (Home, then Delivery); a link that carries filters is taken as written and does not replace the memory. Another prints Cycle time and jumps back to the top: the print button calls the (stubbed) browser print, print media hides the chrome and the filter controls and shows the report's own header (name, filter, generation time), and the floating **Back to top** button appears once the page is scrolled, returns to the top and focuses the heading.
- [`reports-compare.spec.ts`](scenarios/reports-compare.md) — Compare people (report 18), read by a NON-ADMIN user over its own API-seeded, synced
  Jira-stub connection (`e2e-compare-ds-*`, team `e2e-compare-team-*`): the two stub people with the most finished tasks are compared by deep link —
  both in the URL, a column each, the better figure's cell tinted teal (never red) with a check and its words, the Difference column a plain absolute gap, the cycle-time histogram drawing both with a legend; a figure
  opens the drawer whose line equals the cell, Escape closes it with focus back on the figure. The bare page is the pickers' empty state (also swept by `accessibility.spec.ts`).
- [`reports-drill.spec.ts`](scenarios/reports-drill.md) — the drill from a bar to the items behind it, read by a NON-ADMIN user over its own
  API-seeded, synced Jira-stub connection (`e2e-drill-ds-*`) with the FLO board mapped to a throwaway team (`e2e-drill-team-*`): on Throughput a week's
  **Delivered** figure, a link-styled button (the keyboard route), opens a drawer whose server-recomputed summary line equals the table row, whose listed tasks and figures add up to it
  (a clean axe scan with the dialog open); the X, the browser's Back and Escape close it with focus back on the figure, the URL losing `drill`;
  a double-click on the same week's bar opens it again and closing returns focus to the chart's card. On Commitments the golden sprint's Initial and Final
  figures (buttons) list exactly `golden.sprint`'s committed (the very issue keys, each a `target="_blank"` link to `https://flow-e2e.atlassian.net/browse/<KEY>`, asserted and never followed) and final items, a double-click on its first bar opens the committed list, and
  Home's row **Final** figure lists the team's last sprint with a summary equal to the row's Final MD. On Sprint consistency a double-click on the stacked
  partition's carried-over segment lists exactly `golden.sprint`'s carried-over issue keys, the sprint row's figure buttons (the keyboard route, one per bucket that has items: the golden sprint removed nothing, so Removed is plain text) include
  Final, which lists the Final scope (committed + added, the one bucket no chart draws), and the Activity type = Bug slice's biggest bucket lists as many items as its live table cell
  with the page's live-figures note as the drawer caption. On Cycle time a working-days range's count, a button (keyboard),
  or a double-click on its bar opens a drawer whose summary line equals the histogram row's item count and whose listed tasks lie inside the range. On Task accuracy (at start) and Blocked time (blocked working days) the busiest range's count button does the same reconciliation (a bar over 500 items lists the 500 largest and says so), with a clean axe scan and focus back on the count. On Estimate adjustments the stub team's change histogram is withheld (too few adjusted tasks), and the scenario pins that no drill is offered there.
- [`capacity.spec.ts`](scenarios/capacity.md) — the Administration > Capacity page (A32), over its own API-seeded, synced Jira-stub
  connection (`e2e-capacity-ds-*`, not paused: a calendar save must re-derive it) with the FLO board mapped to a throwaway team (`e2e-capacity-team-*`)
  and one stub person on its roster: the admin picks the team and the golden sprint, saves a team default, reads the computed day/person
  defaults, turns a day off, sets a half cell and a 0.8 coefficient, sees the live total equal the hand-computed figure, saves, reloads
  (every edit kept, a clean axe scan of the grid), and Commitments then shows exactly that figure as the sprint's capacity with no "default"
  badge once the connection re-derives; a regular user only reads (every control disabled, no coefficient column), the admin assigns a
  throwaway user as the team's manager through the Teams editor, and the manager edits the team default and a cell, kept across a reload.
- [`public-holidays.spec.ts`](scenarios/public-holidays.md) — the Administration > Public holidays dictionary (Polish statutory holidays 2017-2030 are seeded),
  over its own API-seeded, synced Jira-stub connection (`e2e-holidays-ds-*`) with the FLO board mapped to a throwaway team (`e2e-holidays-team-*`) and one
  stub person on its roster: the admin adds an `E2E Holiday-*` on a weekday of a non-golden sprint's calendar window (a duplicate date is refused inline),
  the Capacity grid then shows that day's column locked off, named and one working day lower, deleting the holiday behind its confirmation frees the day
  again (the dictionary is global: stranded `E2E Holiday*` rows are swept by name); a regular user finds the page in the sidebar, reads the seeded year
  and has no add strip and no delete (a clean axe scan).
- [`teams.spec.ts`](scenarios/teams.md) — the flat-teams registry: create through the modal →
  rename → assign a throwaway user as the team's manager (the Manager picker; the
  details page reads "Manager: …") → delete from the list;
  a regular user's read-only list and team page (no New team, no row menu, no Edit button).
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
  asserts the color-scheme attribute switches and persists, and the two accessibility specs scan the
  page sets in the dark scheme (contrast), but no e2e asserts rendered colors, and there is no
  visual-regression suite.
- **Responsive / cross-browser / visual automation** — the suite deliberately runs a single
  **Desktop Chrome (chromium) project** only, with no mobile project or screenshot comparison;
  layout relies on Mantine semantics plus the role/label-based locators every spec uses.
  Accessibility gets the `accessibility.spec.ts` axe smoke.

Reports/artifacts land in `playwright-report/` and `test-results/` (git-ignored).
