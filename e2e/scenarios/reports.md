# Reports (v0.3.0 M4 commit 14, batch 1 — the non-admin journey)

- **Spec**: [tests/reports.spec.ts](../tests/reports.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed and clean
  up; one throwaway REGULAR user (no roles) who does all the reading through the browser — D12,
  every signed-in user sees every report
- **Owns** (exclusive server-side state): one Jira-stub data source (unique `e2e-reports-ds-*`
  name), one team (unique `e2e-reports-team-*` name) with the stub's FLO board mapped to it, and
  the throwaway reader — all created through the API before the block and deleted after it. The
  synced connection's `raw.*`/`norm.*`/`metrics.*` rows are left in place when the connection is
  soft-deleted (purged only after the grace period, the same deliberate exception the
  data-sources spec documents).

## Setup (once, before the scenarios)

1. Through the admin's API session, the spec creates a Jira-stub data source and syncs it, waiting
   (bounded, ~340s) until its SYNC job has succeeded.
2. It creates a team and maps the stub's **FLO** board to it — a sprint is a team's sprint only
   through its board (D10) — leaving every other metrics setting at its computed default. This
   is the same shape the server's `DerivedStubFixture` derives.
3. It creates the regular user, then polls the report API (bounded, ~240s) until the team's
   velocity lists the golden sprint — the proof that a DERIVE has run under the mapping. The
   figures every scenario below asserts are `sample-data/jira/expected.json`'s `golden.sprint`
   (**FLO Sprint 4**), read from that file at run time.

The default report period (the last 90 days) holds none of the stub's sprints, so every scenario
narrows to the golden sprint through the filter bar: **Team** → the seeded team, **Period** →
"One sprint", **Sprint** → "FLO Sprint 4".

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
- **The Jira-user roster** (team membership) and **team → member drill** — not seeded; the golden
  sprint figures do not read the roster, and D1 membership is covered by `metrics-config.spec.ts`.
- **The "last N sprints" period and the team → member drill** — the journey only uses "One sprint"
  and stops at the team level (no member selected, no drill link followed). Both are deferred to the
  batch-2 e2e in M5.
- **The empty state** of a period with no data — covered by the SPA's `ReportChartCard.test.tsx`.
- **Accessibility of the populated report pages** — `accessibility.spec.ts` scans `/reports/velocity`
  in its default (empty) state; the populated pages are not axe-scanned yet.
