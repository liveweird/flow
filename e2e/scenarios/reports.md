# Reports (v0.3.0 — the non-admin journey: batch 1 M4 commit 14, batch 2 M5 commit 19)

- **Spec**: [tests/reports.spec.ts](../tests/reports.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed and clean
  up; one throwaway REGULAR user (no roles) who does all the reading through the browser — D12,
  every signed-in user sees every report
- **Owns** (exclusive server-side state): one Jira-stub data source (unique `e2e-reports-ds-*`
  name), one team (unique `e2e-reports-team-*` name) with the stub's FLO board mapped to it and ONE
  stub Jira person (a `Sample User N` — D1 allows a person one team at a time, globally, so the
  spec tries a fixed candidate list and takes the first free one) on its roster from 2025, and the
  throwaway reader — all created through the API before the block and deleted after it, the
  membership BEFORE the team (a deleted team keeps its membership rows and would keep the person
  occupied). The
  synced connection's `raw.*`/`norm.*`/`metrics.*` rows are left in place when the connection is
  soft-deleted (purged only after the grace period, the same deliberate exception the
  data-sources spec documents).

## Setup (once, before the scenarios)

1. Through the admin's API session, the spec creates a Jira-stub data source and syncs it, waiting
   (bounded, ~340s) until its SYNC job has succeeded.
2. It creates a team and maps the stub's **FLO** board to it — a sprint is a team's sprint only
   through its board (D10) — leaving every other metrics setting at its computed default. This
   is the same shape the server's `DerivedStubFixture` derives. It then puts one stub person on
   the team's roster, so the cost matrix has a real author-team row.
3. It creates the regular user, then polls the report API (bounded, ~240s each) until the team's
   velocity lists the golden sprint — the proof that a DERIVE has run under the mapping — and
   until the cost matrix names the team as an author team — the proof that a DERIVE has read the
   roster too. The figures the batch-1 scenarios assert are `sample-data/jira/expected.json`'s
   `golden.sprint` (**FLO Sprint 4**), and the epic scenario's are its `golden.epic` (**FLO-33**),
   both read from that file at run time.

The default report period (the last 90 days) holds none of the stub's sprints, so the batch-1
scenarios narrow to the golden sprint through the filter bar: **Team** → the seeded team,
**Period** → "One sprint", **Sprint** → "FLO Sprint 4". The batch-2 scenarios whose numbers are
period-bound read a FIXED window around the stub's reference date (`referenceDate`: from 184 days
before it to 7 days after — the stub's data ends there, so today's date never slides it off the
data) and open their report by deep link, `?connectionId=<this spec's connection>&from=…&to=…`,
because the URL IS the filter: `connectionId` narrows a unit-level report to this spec's connection
(other specs' synced connections would otherwise count), and the team is added where the scenario
reads a team.

## Scenario: a regular user reads the golden sprint's velocity

1. The regular user signs in and opens **Delivery** from the sidebar.
   - *Expected*: the **Velocity** report loads (there is no admin gate on reports).
2. They pick the seeded team, the period "One sprint" and the sprint "FLO Sprint 4".
   - *Expected*: the URL carries `sprintId` and `teamId`; a "Derived … · configuration revision N"
     line and the initial-vs-final chart are shown.
3. They read the sprint's row.
   - *Expected*: sprint name, the team, and **Initial** = the golden committed MD and items,
     **Final** = the golden final MD and items; no drift badge (nothing moved since the sprint
     closed).

## Scenario: the user regroups throughput by month

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Throughput**
   tab.
   - *Expected*: the URL is the throughput route still carrying the sprint and team (the tab
     switch keeps the filter).
2. They read the sprint view.
   - *Expected*: the golden sprint's row shows the golden delivered MD and items.
3. They read the period view, grouped by week (the default).
   - *Expected*: a "Delivered in the period: N MD (M items)" summary; the period table's first
     column reads "Week starting", with one dated row per week across the sprint's window (more
     than one).
4. They change **Group by** to **Month**.
   - *Expected*: the URL gains `bucket=MONTH`; the table's first column now reads "Month" and
     holds one `YYYY-MM` row (the whole window falls in one month); the chart is still shown.

## Scenario: the user reads the golden sprint's scope movement

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Sprint
   consistency** tab.
   - *Expected*: the committed/delivered and the final-scope-partition charts are shown.
2. They read the "All figures" table's row for the sprint.
   - *Expected*: committed, added, removed, final, delivered, carried over and dropped each show
     the golden `MD (items)` pair — the A17 partition the report exists to make visible.

## Scenario: the user reads cycle time

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Cycle time**
   tab.
   - *Expected*: both the "Working days" and "Elapsed days" views are shown, each with its own
     "Of N finished in this period" accounting, and each either a percentile strip or the
     counts-only minimum-sample notice.
   - *Expected*: the per-period trend table is present (the team finished work in the window).

## Scenario: the user reads task estimation accuracy

1. The user opens **Estimation** from the sidebar.
   - *Expected*: the **Task estimation accuracy** report loads.
2. They narrow to the golden sprint.
   - *Expected*: the "Against the estimate at start" (primary) and "Against the estimate at done"
     views are both shown, each followed by its "Left out of this distribution" accounting, and
     each either a percentile strip or the counts-only notice.

## Scenario: the user reads the last three sprints and drills to a member

1. The user opens **Delivery**, picks the seeded team and the period "Last 3 sprints".
   - *Expected*: the URL carries `lastSprints=3` and the team; the sprint table holds three rows,
     the team's three newest sprints.
2. They read the "By member" table and note the first member's name and **Initial** man-days.
   - *Expected*: the table lists members by name (people who held work in those sprints; no roster
     is needed for this level).
3. They follow that member's name link.
   - *Expected*: the URL gains `accountId`; the "By member" table is gone (nothing is below a
     person); the person's sprint rows add up (initial MD) to the figure the team level showed for
     them.

## Scenario: the user counts work in progress by stage and by status

1. The user opens the **WIP** report for the seeded team (deep link, fixed window).
   - *Expected*: the stacked-bands chart is shown, and its text alternative — the per-band table —
     lists the four stages (Not started, In progress, Done, Unmapped status).
2. They change **Count by** to **Status**.
   - *Expected*: the URL gains `by=STATUS`; the caption says the bands are by status; the table now
     lists the workflow's own statuses (To Do, In Progress, Done) and no longer the "Not started"
     stage; the chart is still shown.

## Scenario: the user reads aging work in progress and blocked time

1. The user opens **WIP** for the seeded team and switches to the **Aging WIP** tab.
   - *Expected*: the URL is the aging route and still carries the team; the Tasks thresholds tile
     row shows p50, p85 and p95 (the team finished plenty); the "Open work, oldest first" table has
     its "Age (working days)" column and at least one item, keyed like `FLO-12`.
2. They switch to the **Blocked time** tab.
   - *Expected*: the "Blocked working days" and "Blocked share of cycle" views are both shown, each
     a percentile strip or the counts-only notice, with the "N of M finished items were blocked at
     all" line and the "Most blocked items" block.

## Scenario: the user drills from a domain into the golden epic's progress

1. The user opens **Epic progress** for this spec's connection (deep link, fixed window).
   - *Expected*: the "Whole unit" card shows the PV, EV, AC, SPI and CPI tiles; the Domains table
     links "FLO" and the Teams (sprint view) table links the seeded team.
2. They follow the FLO domain link.
   - *Expected*: the URL gains `domain=FLO` and keeps the connection; the card reads "Domain: FLO";
     the Epics table lists the golden epic.
3. They follow the golden epic's link (FLO-33).
   - *Expected*: the URL gains `epicId=FLO-33`; the card is titled with the epic; the breadcrumb
     offers the way back through "FLO"; the PV tile equals the epic's budget (the plan is fully
     spent long before the window ends); the Plan panel shows the budget and the planned start and
     due dates — all three from `expected.json`'s `golden.epic`.
4. They open the daily figures under the chart.
   - *Expected*: the chart's text alternative lists the days newest first, ISO dates, one row per
     day of the window (more than a hundred).

## Scenario: the user reads the data-quality findings

1. The user opens **Data quality** for this spec's connection (deep link, fixed window).
   - *Expected*: the overview names what was checked (done tasks, …) and its headline tiles.
2. They compare three findings the stub guarantees with their cards: work logged more than a day
   late, authors without a team, and work done outside any sprint (the OPS Kanban board).
   - *Expected*: each tile is above zero, and its card states the same count in its "Found: N" badge;
     the outside-sprint card lists OPS items.
3. They follow the "Authors without a team" tile.
   - *Expected*: focus lands on that card and the URL is unchanged (the tile writes no hash).
4. They read the configuration findings.
   - *Expected*: "Boards without a team" has findings, and the page has NO "Open the metrics
     configuration of …" link — that admin shortcut is for administrators only.

## Scenario: the user reads the cost matrix and drills to a team

1. The user opens the **Cost matrix** for this spec's connection (deep link, fixed window).
   - *Expected*: the heat table shows the stub's four domains as columns plus Total, Foreign work
     and Foreign share; the seeded team is a row (its roster person's logged time); a totals row
     closes the table.
2. They read the totals.
   - *Expected*: the totals row's Total equals the "Man-days logged" tile, and the rows' totals add
     up to it (each figure is rounded once, so within the page's stated 0.005 apiece).
3. They follow the team's name link.
   - *Expected*: the URL gains the team; the card reads "Man-days by author and domain"; the table's
     first column is "Author" and lists the roster person.

## Scenario: the user sees the four overview tiles on Home and follows one into its report

1. The user signs in and lands on Home, the whole-unit overview.
   - *Expected*: the four tiles — Velocity and throughput, Cycle time, Work in progress, Data
     quality — are shown, none still loading, each with its title as a link; the velocity tile
     lists the seeded team with its last closed sprint.
2. They follow the **Cycle time** tile's title.
   - *Expected*: the Cycle time report opens with the tile's own period in the URL (`from`/`to`
     dates) — a tile link is never bare, since a bare link would apply the remembered team.
3. They go back Home and follow the **Velocity and throughput** tile's title.
   - *Expected*: the Velocity report opens with `lastSprints=1` in the URL.

## Scenario: the populated flow, epic and cost pages have no WCAG A/AA violations

1. The user opens WIP (the seeded team), Epic progress (the golden epic) and the Cost matrix by
   deep link, each waiting for its content (the band table, the Plan panel, the heat table).
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included, no waivers) of each
     page reports zero violations — the populated counterpart of `accessibility.spec.ts`, which
     scans the same pages in whatever state the stack happens to be in.

## Scenario: the populated Home overview and data-quality page have no WCAG A/AA violations

1. The user signs in, lands on Home and waits for its four tiles to finish loading, then scans it.
   - *Expected*: an axe scan (same tags, no waivers) reports zero violations — including the velocity
     tile's sideways-scrolling table, which is a focusable, named scroll region.
2. They open **Data quality** for this spec's connection (deep link, fixed window), wait for the
   findings and scan it.
   - *Expected*: zero violations — including the drift table's orange "reconstructed" badge.

## Not covered here (and why)

- **The figures of the period views** (throughput's weekly/monthly MD, accuracy ratios, cycle-time
  percentiles) — the journey asserts their presence and shape, not their numbers: they depend on
  the whole stub dataset's worklogs and estimates and are pinned exactly, against independent
  recomputations of the persisted facts, by the server's `Report*Test` classes. The golden sprint's
  figures are the one place the browser journey pins numbers, because they are written into
  `expected.json`.
- **Epic accuracy, estimate adjustments, reported time** — sibling tabs of the estimation group
  with the same components; covered by their own server tests and `web/src/pages/Report*.test.tsx`.
  A later batch can extend this journey if the risk warrants it.
- **Managing the Jira-user roster** — D1 membership editing is covered by `metrics-config.spec.ts`;
  here the roster is one API-seeded person, only so the cost matrix has an author-team row.
- **The figures of the batch-2 pages** — the WIP counts, the aging bands and thresholds, the blocked
  shares, the data-quality counts, the cost matrix's man-days and EVM's SPI/CPI depend on the whole
  stub dataset and the clock; they are pinned against independent recomputations by the server's
  `Report*Test` classes. The journeys assert structure, self-consistency (a tile equals its card, the
  totals add up, a member's sprints add up to the team level's figure) and the few figures the
  generator writes down (`golden.epic`).
- **The remaining tabs and pages** — Estimated backlog, Epic accuracy, Adjustments and Reported
  time; covered by `web/src/pages/Report*.test.tsx` and the server tests.
- **The empty state** of a period with no data — covered by the SPA's `ReportChartCard.test.tsx`.
