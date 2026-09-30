# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) for the authenticated pages; an
  anonymous visitor for the login screen
- **Owns** (exclusive server-side state): one fixture team (unique `e2e-axe-*` name), created
  through the API before the detail-page block and deleted after it; one Jira-stub data source
  (`e2e-axe-ds-*`), synced and derived through the API before the "pages over synced data" block,
  with a team (`e2e-axe-data-team-*`) its FLO board is mapped to, both deleted after it (the
  connection's `raw.*`/`norm.*`/`metrics.*` rows stay, the same deliberate exception `reports.spec.ts`
  documents); the list/form pages and the login screen are read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for each list.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero
     violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

The list covers the Home page, the Teams list, the Users list and its create form, the
ADMIN feature-flags screen, the Data sources list, the Metrics settings form (v0.3.0), every
report route (all fifteen, v0.3.0 — each scanned in whatever state the stack gives it; the same
pages with derived data behind them are scanned below and by `reports.spec.ts`'s "the populated …
pages have no WCAG A/AA violations"), Change password and the Changelog — see
`tests/accessibility.spec.ts`'s `AUTHED_PAGES` list for the current set.

## Scenario: `<path>` has no WCAG A/AA violations in the dark scheme

1. The browser context emulates the dark colour scheme (Playwright `colorScheme: "dark"`; the
   app's `auto` default follows it). The admin signs in, opens `<path>` (the same list as above)
   and waits for its heading.
   - *Expected*: `<html>` carries `data-mantine-color-scheme="dark"` — the scan really runs in the
     dark palette — and an axe scan (same tags, `color-contrast` included) reports zero violations.

## Scenario: login screen has no WCAG A/AA violations in the dark scheme

1. An anonymous visitor with the dark scheme emulated opens `/login` and waits for the sign-in form.
   - *Expected*: the dark scheme is on `<html>` and the axe scan reports zero violations.

## Scenario: `<page>` has no WCAG A/AA violations over synced data

1. Before the block, the admin's API session syncs a Jira-stub data source (waiting, bounded,
   for its SYNC job), creates a team, maps the FLO board to it and waits until a DERIVE has
   produced the golden sprint in the team's velocity.
2. The admin signs in and opens `<page>` — each of the fifteen reports by deep link, narrowed to this
   connection (`connectionId`) and to the year before the stub's reference date through a week
   after it (so the stub's 2025 sprints and epics are inside), with the seeded team on the
   team-scoped ones (velocity, throughput, sprint consistency, cycle time, task accuracy, WIP,
   backlog, aging WIP, blocked time); then the data source's details page (the raw-issue count shows
   the full dataset), its data profile, the raw issue inspector on `FLO-1` and its metrics
   configuration — and waits for the page to settle (the title, no spinner left, no error alert).
   - *Expected*: an axe scan (same tags, no waivers) reports zero violations — charts, histograms,
     heat table, sideways-scrolling tables (focusable named regions) and the admin pages with data.
3. After the block, the API session deletes the connection and the team.

## Scenario: `<page>` has no WCAG A/AA violations over synced data in the dark scheme

1. The same pages as above, in a context emulating the dark scheme.
   - *Expected*: `<html>` is in the dark scheme and the scan reports zero violations.

## Scenario: `<detail page>` has no WCAG A/AA violations

1. Before the block, the admin's API session seeds one team (`e2e-axe-team-*`).
2. The admin signs in and opens the page — the team's roster page, their own edit-user and
   user-features pages — and waits for its settled element (the heading).
   - *Expected*: an axe scan (same tags) reports zero violations.
3. After the block, the API session deletes the team.

## Scenario: the reset-password page has no WCAG A/AA violations

1. An anonymous visitor opens `/reset-password` and waits for its heading.
   - *Expected*: zero violations.

## Scenario: the not-found page has no WCAG A/AA violations

1. The admin opens an address that matches no route.
   - *Expected*: the not-found page renders inside the shell with zero violations.

## Scenario: `<overlay>` has no WCAG A/AA violations

1. The admin opens the overlay — the New team editor modal from the Teams page — and waits
   for the dialog.
   - *Expected*: an axe scan scoped to the dialog reports zero violations (focus trap, `aria-modal`,
     labelled close button, contrast).

## Not covered here (and why)

- **Interactive journeys mid-flight** (the one-time password reveal, a confirm mid-transition)
  — those need the journey that produces them; covered by their own journey specs where
  reachable through the ordinary flow.
- **The golden epic's plan panel and Home's populated tiles** — scanned by `reports.spec.ts` (light
  scheme); the dark scheme covers every page of this spec's sets, not those two states.
- **Modals and detail pages in the dark scheme** — the dark pass covers the page sets above, not the
  overlay or the fixture team's detail pages.
- **Colour tokens themselves** — the ratios are pinned in `web/src/theme.test.ts`; axe here
  verifies the rendered pages honour them.
