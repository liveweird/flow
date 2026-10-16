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

## Not covered here (and why)

- The stacked per-person segments and the share-table rows (`segmentAccountId`): they need a
  picked-members pick on a team roster; the server tests reconcile every segment against `byUser`
  and the page tests pin the URL and the select, so an end-to-end pick adds a roster seed for no
  new risk.
- The sprint and distribution charts: they arrive with their own drill and their own scenario here.
