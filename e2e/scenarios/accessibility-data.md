# Accessibility scans over synced data (axe, WCAG A/AA)

- **Spec**: [tests/accessibility-data.spec.ts](../tests/accessibility-data.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API to seed and clean up and
  through the browser to read
- **Owns** (exclusive server-side state): one Jira-stub data source (`e2e-axe-ds-*`), synced and
  derived through the API before the block, with a team (`e2e-axe-data-team-*`) its FLO board is
  mapped to; the connection is PAUSED (`enabled: false`) once derived, so config changes elsewhere in
  the suite never re-derive it, and both are deleted after the block (the connection's
  `raw.*`/`norm.*`/`metrics.*` rows stay, the same deliberate exception `reports.spec.ts` documents)

Split from `accessibility.spec.ts` so the sync + DERIVE wait runs beside its light/dark passes and a
failing test re-syncs only this file. The registered template-title exception (see README.md): one
`test()` per page is generated from a list, so a single scenario section stands in for each list.

## Scenario: `<page>` has no WCAG A/AA violations over synced data

1. Before the block, the admin's API session syncs a Jira-stub data source (waiting, bounded,
   for its SYNC job), creates a team, maps the FLO board to it, waits until a DERIVE has
   produced the golden sprint in the team's velocity, and pauses the connection.
2. The admin signs in and opens `<page>` — each of the sixteen report pages by deep link (the Deep dive with the golden
   sprint of the FLO domain selected, so its matrix is drawn, and once more on its Burn-up view, `view=burnup`, so the
   chart and its legend are drawn), narrowed to this
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

- **The golden epic's plan panel and Home's populated tiles** — scanned by `reports.spec.ts` (light
  scheme).
- **Modals and detail pages in the dark scheme** — the dark pass covers the page sets above, not
  overlays or the fixture team's detail pages (`accessibility.spec.ts`).
