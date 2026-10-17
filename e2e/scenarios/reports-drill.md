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

## Not covered here (and why)

- The stacked per-person segments and the share-table rows (`segmentAccountId`): they need a
  picked-members pick on a team roster; the server tests reconcile every segment against `byUser`
  and the page tests pin the URL and the select, so an end-to-end pick adds a roster seed for no
  new risk.
- The Home chart's own double-click: every team of every concurrent spec shares that chart, so a
  bar cannot be told apart reliably; the page tests pin the chart's `{row, series}` → bucket map and
  this scenario drives the same drawer through the row's Items menu.
- Throughput's sprint chart and the distribution charts: they arrive with their own scenario here
  (the server tests reconcile every sprint bar and the page tests pin the Throughput sprint wiring).
- Sprint consistency's other bars (committed, delivered, dropped, added, removed): the server tests
  reconcile all seven buckets with the figures and the page tests pin every series → bucket
  mapping; the pointer route is exercised end to end on one segment, the keyboard route on Final.
