# Reports drill — from a bar to the items behind it (the non-admin journey)

- **Spec**: [tests/reports-drill.spec.ts](../tests/reports-drill.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed and clean
  up; one throwaway REGULAR user (no roles) who does all the reading through the browser — D12,
  every signed-in user sees every report
- **Owns** (exclusive server-side state): one Jira-stub data source (unique `e2e-drill-ds-*` name),
  one team (unique `e2e-drill-team-*` name) with the stub's FLO board mapped to it, and the
  throwaway reader — all created through the API before the block and deleted after it. The
  synced connection's `raw.*`/`norm.*`/`metrics.*` rows are left in place when the connection is
  soft-deleted, exactly as `reports.spec.ts` leaves them. It has its own connection (one extra
  sync + derive) so the much larger `reports.spec.ts` stays untouched.

Each chart family gets its own scenario here as its drill lands; every one reconciles the drawer
against the figure the page already shows (the table row, never a recomputation in the test).

## Scenario: throughput: a week's Items button lists the tasks behind the bar, the list reconciles with the row, Back and browser Back restore the chart

1. The user signs in and opens **Throughput** over the spec's own connection and team, for the
   golden sprint's period.
   - *Expected*: the heading "Throughput", the period chart, the dimmed hint "Double-click a bar to
     list the items behind it — or use a row's Items button.", and an **Items** column in the
     "Delivered per period" table.
2. They read the period table and pick the week that delivered the most MD, then focus that row's
   **Items** button ("Show the items of <week>") and press Enter — the keyboard route.
   - *Expected*: a dialog named "<week> · Delivered" opens and the URL gains `drill=bucket…`.
3. They read the drawer.
   - *Expected*: its summary line is the server's own recomputation and equals the row — "<n> items
     · <md> MD" — the list has exactly one table row per item, the listed figures add up to the
     row's MD (an unestimated item is a dash and worth 0), and there is no "The data changed since
     the chart was drawn." note. An axe scan with the dialog open finds nothing.
4. They press **Back to the chart**.
   - *Expected*: the drawer closes, the URL loses `drill`, and focus is back on the Items button.
5. They open it again and use the browser's Back button.
   - *Expected*: the drawer closes and the page is still Throughput for the same sprint, without
     `drill` in the URL.
6. They open it again and press Escape.
   - *Expected*: the drawer closes.
7. They double-click the same week's bar in the chart, then close the drawer with its **X**.
   - *Expected*: the same drawer ("<week> · Delivered", the same summary line) opens; closing it
     returns focus to the chart's card (the pointer route has no button to return to).

## Scenario: commitments and Home: the golden sprint's committed and final items reconcile with the figures, by Items button and by double-click, and Home's Items menu lists a team's last sprint

1. The user signs in and opens **Commitments** over the spec's own connection and team, for the
   golden sprint.
   - *Expected*: the heading "Commitments", the initial/final chart, the dimmed drill hint, and an
     **Items** column in the "Sprints and their figures" table.
2. They focus the sprint row's **Items** button for the committed bar ("Show the items of <sprint> ·
   Committed" — a sprint row has one button per bar) and press Enter.
   - *Expected*: a dialog named "<sprint> · Committed" opens and the URL gains
     `drill=sprint:<teamId>:<id>:COMMITTED`.
3. They read the drawer against `sample-data/jira/expected.json`'s `golden.sprint`.
   - *Expected*: the summary line is "<committedItems> items · <committedMd> MD", the list has one
     table row per item, the listed issue keys are exactly `committedIssueKeys`, the estimates add up
     to the committed MD, and there is no "The data changed since the chart was drawn." note.
4. They press **Back to the chart**.
   - *Expected*: the drawer closes, the URL loses `drill`, focus is back on the Items button.
5. They press the same row's **Final** Items button, then Escape.
   - *Expected*: "<sprint> · Final" lists `finalItems` items and `finalMd` MD (committed + added);
     Escape closes it.
6. They double-click the sprint's first (initial) bar in the chart.
   - *Expected*: the same committed drawer opens with the same summary line; its **X** closes it.
7. They open **Home** and find their team's row in the commitments tile, whatever its last closed
   sprint is, and open the row's **Items menu** ("Operations for <team> · <sprint>") and choose
   **Final**.
   - *Expected*: a dialog "<team> · <sprint> · Final" opens, the URL is `/?drill=sprint:<teamId>:<id>:FINAL`,
     its summary line carries exactly the Final MD printed in the row, and there is no
     changed-data note. The browser's Back button closes it, without `drill` in the URL.

## Scenario: sprint consistency: a carried-over segment's items, the final scope's Items menu entry and the sliced (Bug) list reconcile with the figures

1. The user signs in and opens **Sprint consistency** over the spec's own connection and team, for
   the golden sprint.
   - *Expected*: the heading "Sprint consistency", the three charts each with the dimmed drill hint,
     and the "Sprints and their figures" table.
2. They double-click the first segment of the stacked "where the final scope ended up" chart (bottom
   to top: carried over, delivered, dropped).
   - *Expected*: a dialog named "<sprint> · Carried over" opens and the URL gains
     `drill=sprint:<teamId>:<id>:CARRIED_OVER`; its summary line is `golden.sprint`'s "<carriedOverItems>
     items · <carriedOverMd> MD", the list has one table row per item, the listed issue keys are
     exactly `carriedOverIssueKeys`, the estimates add up to the carried-over MD, there is no "The data
     changed since the chart was drawn." note and no live-figures caption (nothing is sliced).
3. They close the drawer with its **X**.
   - *Expected*: focus returns to the partition chart's card (the pointer route has no button).
4. They focus the sprint row's **Operations** button and press Enter.
   - *Expected*: its menu has one entry per bucket, in the table's column order — Committed, Added,
     Removed, Final, Delivered, Carried over, Dropped.
5. They choose **Final** (the one bucket no chart draws), then press Escape.
   - *Expected*: "<sprint> · Final" lists `finalItems` items and `finalMd` MD, the very issue keys of
     the committed plus the added items; Escape closes it and focus is back on the Operations button.
6. They open the same page sliced by **Activity type = Bug** (live figures, no drift) and read the
   sprint row's seven "MD (items)" cells; they choose the bucket with the most items from the Items
   menu.
   - *Expected*: the drawer's summary line equals that cell ("<n> items · <md> MD"), it lists one
     table row per item, repeats the page's "Sliced by activity type or work category…" note as its
     caption, and shows no changed-data note. The browser's Back button closes it; the page is still
     sliced (`activityType=Bug`) and the URL has no `drill`.

## Scenario: cycle time: a working-days range's Items button lists the tasks inside the range, the list reconciles with the histogram row, Back restores the page

1. The user signs in and opens **Cycle time** over the spec's own connection and team, for a year-long
   window (the stub's work finished in autumn 2025).
   - *Expected*: the heading "Cycle time", the working-days histogram table with an **Item list**
     column, and the dimmed hint "Double-click a bar to list the items behind it — or use the button in
     a row's Item list column."
2. They read the working-days histogram table and pick the busiest range that is not the open tail,
   then focus that row's button in the **Item list** column ("Show the items of Working days: <range>") and press
   Enter — the keyboard route.
   - *Expected*: a dialog named "<range> · Working days" opens and the URL gains
     `drill=hist:WORKING_DAYS:<index>`.
3. They read the drawer.
   - *Expected*: its summary line is the server's own recomputation and equals the row's item count
     ("<n> items"); the list has one table row per task (at most 500: a bar with more says "Showing the 500
     largest of <n> items." and the summary line keeps the full count); every listed working-days figure
     lies inside the range the row names (the labels are rounded to one decimal, so an edge value
     may read a tenth either side); the figure column is headed "Working days"; there is no "The
     data changed since the chart was drawn." note. Once the drawer's transition has settled, an axe scan with the dialog open finds nothing.
4. They press **Back to the chart**, then open it again and use the browser's Back button.
   - *Expected*: each time the drawer closes, the URL loses `drill`, and after the first close focus
     is back on the Items button; the page is still Cycle time.
5. They double-click that range's bar in the histogram, then close the drawer with its **X**.
   - *Expected*: the same drawer ("<range> · Working days", the same item count) opens; closing it
     returns focus to the tasks card (the pointer route has no button to return to).

## Scenario: task accuracy and blocked time: a ratio range and a blocked-days range drill to their items, each list reconciling with its histogram row

1. The user signs in and opens **Task estimation accuracy** over the spec's own connection and team, for
   a year-long window.
   - *Expected*: the heading "Task estimation accuracy" and the at-start histogram table
     ("Histogram, as a table — Against the estimate at start") with an **Item list** column.
2. They read that table, pick the busiest range that is not the open tail, then focus its row's
   button ("Show the items of Against the estimate at start: <range>") and press Enter.
   - *Expected*: a dialog named "<range> · Against the estimate at start" opens and the URL gains
     `drill=hist:AT_START:<index>`.
3. They read the drawer.
   - *Expected*: its summary line equals the row's item count; the list has one table row per task (at most 500; a bigger bar says "Showing the 500 largest of <n> items.");
     every listed ratio lies inside the range the row names (the labels are rounded, so an edge value
     may read a tenth either side); the figure column is headed "Actual ÷ estimate"; there is no "The
     data changed since the chart was drawn." note; once the drawer's transition has settled, an axe
     scan with the dialog open finds nothing.
4. They press **Back to the chart**.
   - *Expected*: the drawer closes, the URL loses `drill` and focus is back on the Items button.
5. They open **Blocked time** over the same connection and window and repeat steps 2–4 on its
   blocked-working-days histogram ("Show the items of Blocked working days: <range>", dialog
   "<range> · Blocked working days", `drill=hist:BLOCKED_WORKING_DAYS:<index>`, figure column
   "Blocked working days").
   - *Expected*: the same reconciliation — the summary line equals the row's count, one list row per
     item, every figure inside the range — and the same clean axe scan, close and focus return.

## Scenario: estimate adjustments: a withheld change histogram offers no drill, and the page's other figures stay

1. The user signs in and opens **Estimate adjustments** over the year-long window with the spec's own team.
   - *Expected*: the heading "Estimate adjustments"; the "Tasks" block says only a few items are in the
     selection ("Only <n> items in this selection — at least <min> are needed to show percentiles and a
     histogram.") because the change histogram charts only the tasks whose estimate CHANGED and the stub's
     FLO board has fewer than the minimum sample size of them; the "Not charted: <n> finished items with
     an unchanged estimate" line is still there.
2. They look for a drill on the page.
   - *Expected*: no "Histogram, as a table" appears, there is no **Item list** column, no "Show the items
     of …" button and no "Double-click a bar…" hint — the drill is offered exactly where a histogram is
     drawn. (The chart-and-table drill of the change histogram, with its reconciliation against `byUser`
     and the hand-built exclusions, is the server's `ReportDrillAdjustmentsTest` and the page's tests.)

## Not covered here (and why)

- The stacked per-person segments and the share-table rows (`segmentAccountId`): they need a
  picked-members pick on a team roster; the server tests reconcile every segment against `byUser`
  and the page tests pin the URL and the select, so an end-to-end pick adds a roster seed for no
  new risk.
- The Home chart's own double-click: every team of every concurrent spec shares that chart, so a
  bar cannot be told apart reliably; the page tests pin the chart's `{row, series}` → bucket map and
  this scenario drives the same drawer through the row's Items menu.
- Throughput's sprint chart: the server tests reconcile every sprint bar and the page tests pin its wiring
  (the Commitments scenario drives the same sprint drawer end to end).
- Sprint consistency's other bars (committed, delivered, dropped, added, removed): the server tests
  reconcile all seven buckets with the figures and the page tests pin every series → bucket
  mapping; the pointer route is exercised end to end on one segment, the keyboard route on Final.
- A person's segment of a stacked histogram: the spec's team has one stub person, so the stacked
  view (two or more picks) and the drawer's person select are the page tests' and the server
  reconciliation's (every segment equals the histogram's `byUser` count).
- The epic histograms, the reported-time page and the elapsed-days histogram: the same shared
  drill component, pinned by their page tests and the server tests; one end-to-end histogram
  covers the wiring.
- The adjustments change histograms' drawer: the stub's team has too few adjusted tasks to clear the
  minimum sample size (and the minimum is a global setting other specs share), so the end-to-end
  scenario above pins the withheld state; the drawer is `ReportDrillAdjustmentsTest` (every bucket,
  the exclusions, the segments) and `ReportEstimateAdjustmentsDrill.test.tsx`.
- Epic accuracy and the epics block of adjustments: they share the drill component and the server
  reconciliation with their task twins (no people either).
- Data quality's late-logging histogram: it has no drill (its `worst` list already shows the
  latest-logged worklogs).
- The pointer route (a double-click) and the person segments of task accuracy and blocked time: the
  cycle-time scenario pins the pointer wiring of the shared component, and the page tests pin each
  page's `{row, series}` → bar map and the segment select; blocked time's `BOTH` item kind (the
  Kind column in the drawer) is a page test too.
