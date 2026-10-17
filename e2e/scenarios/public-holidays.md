# Public holidays (Administration > Public holidays, and what a holiday does to the Capacity grid)

- **Spec**: [tests/public-holidays.spec.ts](../tests/public-holidays.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) through the browser; a throwaway REGULAR user (no roles) who only
  reads. The seed administrator's API session seeds and cleans up
- **Owns** (exclusive server-side state): one Jira-stub data source (unique `e2e-holidays-ds-*` name, synced), one team (unique
  `e2e-holidays-team-*` name) with the stub's FLO board mapped to it and ONE stub Jira person on its roster from 2025, the
  throwaway user, and the holiday rows it adds (named `E2E Holiday-*`; the dictionary is GLOBAL, so a holiday re-derives every
  connection when it is added and again when it is deleted — it lives only for the one test, is deleted by that test, deleted again
  in `afterAll`, and every stranded `E2E Holiday*` row of an aborted run is swept by name before and after). All created through
  the API before the blocks and deleted after them, the roster membership BEFORE the team (a deleted team keeps its membership
  rows and would keep the person occupied). The synced connection's `raw.*`/`norm.*`/`metrics.*` rows are left in place when it is
  soft-deleted (the data-sources spec's deliberate exception). The sprint it reads is the OLDEST non-golden sprint of the team (a holiday dated that early re-derives the fewest other figures): the
  capacity spec runs concurrently and asserts every weekday of the golden sprint's window counted

## Setup (once, before the first scenario)

1. Any leftover `E2E Holiday*` row is swept through the API.
2. Through the admin's API session, the spec creates a Jira-stub data source and syncs it (bounded, ~340s), creates a team, maps
   the stub's **FLO** board to it, puts one stub person on its roster, and polls Commitments (bounded, ~240s) until the golden
   sprint appears — the proof that a DERIVE ran under the mapping.
3. It picks the oldest other sprint of the team from the team's sprint-calendars list (a closed sprint with a window of at most 30 days).

## Scenario: an administrator adds a public holiday, the Capacity grid locks that day and deleting it frees the day

1. The admin opens that sprint's calendar by its deep link (`/capacity?teamId=…&sprintId=…`) and reads its window from the caption.
   - *Expected*: the computed defaults count every weekday that is not already a holiday in the dictionary (the test chooses a
     weekday of those, the earliest); "This calendar" equals their number and the day's checkbox reads **Count <day>**, ticked.
2. They open **Public holidays**, type the day in **Date** and a unique `E2E Holiday-…` name in **Name**, and click **Add holiday**.
   - *Expected*: the toast "Public holiday added — reports re-derive shortly"; the **Year** picker moves to the holiday's year and
     the table of that year lists the date and the name.
3. They add the same date again under another name.
   - *Expected*: the inline message "A holiday already exists on this date." (a `409`), no toast.
4. They open the sprint's calendar again.
   - *Expected*: the day's column is locked — its checkbox is named "<day> is a public holiday: <name>", unticked and disabled; the
     column header's tooltip reads "Public holiday: <name>"; the legend gains "Public holiday (always not counted)"; the person's cell
     for that day is disabled; "This calendar" is one working day lower.
5. They open **Public holidays**, pick the holiday's year, click the row's **Delete the holiday <name>** button and confirm.
   - *Expected*: the toast "Public holiday deleted — reports re-derive shortly"; the row is gone.
6. They open the sprint's calendar a last time.
   - *Expected*: the day is an ordinary working day again — **Count <day>** is ticked and "This calendar" is back to its first figure.

## Scenario: a regular user reads the seeded public holidays but cannot change them

1. The throwaway regular user signs in and clicks **Public holidays** in the Administration section of the sidebar.
   - *Expected*: the page opens at `/public-holidays` with its title and the line saying the list is maintained by administrators.
2. They pick **2026** in the **Year** picker.
   - *Expected*: the table "Public holidays of 2026" lists the migration's Polish statutory holiday 2026-11-01, Wszystkich Świętych.
3. They look for ways to change the list.
   - *Expected*: there is no **Add holiday** button, no **Date** field and no **Delete the holiday …** button; an axe WCAG A/AA scan of
     the page is clean.

## Not covered here (and why)

- The page's mechanics (the year filter, the 400/403/404/409 messages, the confirmation's Cancel) and the grid's holiday column are
  unit-tested in `web/src/pages/PublicHolidays.test.tsx`, `web/src/components/SprintCalendarGrid.test.tsx`,
  `web/src/pages/Capacity.test.tsx` and `web/src/utils/capacity.test.ts`; the server rules (seed, uniqueness, who may write, the
  re-derive, holidays in the default capacity and in every report's working days) by the server suite.
