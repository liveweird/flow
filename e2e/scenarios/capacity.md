# Capacity (A32 — the Administration > Capacity page)

- **Spec**: [tests/capacity.spec.ts](../tests/capacity.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`) through the browser; a throwaway REGULAR user (no roles) who
  only reads; a throwaway user the administrator makes the team's manager and who then edits. The seed administrator's
  API session seeds and cleans up
- **Owns** (exclusive server-side state): one Jira-stub data source (unique `e2e-capacity-ds-*` name, synced, deliberately
  NOT paused — a calendar save bumps the shared config revision and the CALENDAR figure only reaches the report once the
  connection re-derives), one team (unique `e2e-capacity-team-*` name) with the stub's FLO board mapped to it and ONE
  stub Jira person on its roster from 2025, the team's stored default figure and the golden sprint's stored calendar, and
  the two throwaway users — all created through the API before the block and deleted after it, the roster membership
  BEFORE the team (a deleted team keeps its membership rows and would keep the person occupied). The synced connection's
  `raw.*`/`norm.*`/`metrics.*` rows are left in place when it is soft-deleted (the data-sources spec's deliberate exception)

## Setup (once, before the scenarios)

1. Through the admin's API session, the spec creates a Jira-stub data source and syncs it, waiting (bounded, ~340s) until its
   SYNC job has succeeded.
2. It creates a team, maps the stub's **FLO** board to it, and puts one stub person on its roster.
3. It creates the two throwaway users, then polls the Commitments API (bounded, ~240s) until the team lists the golden sprint
   (`sample-data/jira/expected.json`'s `golden.sprint`, **FLO Sprint 4**) — the proof that a DERIVE ran under the mapping.

The file holds the Playwright lock `REPORTS_EXCLUSIVE_LOCKS.capacity` (its connection is live and re-derives on every calendar save), which `reports.spec.ts` also
holds: the two never run at the same time, whatever the dispatch order.

## Scenario: an administrator plans a sprint calendar and Commitments shows the calendar capacity

1. The admin signs in and opens **Capacity** (not an admin-only page, but the admin is the actor here).
   - *Expected*: the page title, and the prompt to pick a team and a sprint.
2. They pick the seeded team and the golden sprint from the two pickers.
   - *Expected*: the URL carries `teamId` and `sprintId`; the **Team default capacity** card and the **Sprint calendar** card appear.
3. In the team default card they enter 40 and **Save default**.
   - *Expected*: the toast "Saved — reports re-derive shortly".
4. They read the sprint calendar's computed defaults.
   - *Expected*: the note "Showing computed defaults. Save to store this calendar."; "Capacity in reports: …" is shown
     separately; every weekday of the sprint's window is counted and every Saturday and Sunday is not (computed here from the
     window's dates) — except the window's first day, which the previous sprint of the stub's chain already holds (A42): its
     checkbox is named "<day> is counted in <sprint>", unticked and disabled, its cells are disabled and the legend says
     "Counted in an earlier sprint (not counted here)"; the person's cells read 1, the team coefficient 1, and "This calendar"
     equals the number of working days other than that one.
5. They turn the first counted working day off, set the second counted working day's cell to 0.5 and the person's coefficient to 0.8.
   - *Expected*: the turned-off day's cell is disabled; "This calendar" equals 0.8 × (N − 2 + 0.5) for N working days, with
     "Saving it makes reports use it." beside it.
6. They **Save calendar**, then reload the page.
   - *Expected*: the toast; after the reload the defaults note is gone and the day, the cell, the coefficient, the team default
     (40) and the total are all kept. An axe scan of the populated grid is clean.
7. They wait (bounded, ~240s) for the Commitments API to report the sprint's capacity from the calendar (`capacitySource`
   CALENDAR at that figure), then open Commitments for the team and the golden sprint.
   - *Expected*: the sprint's **Capacity (MD)** cell shows exactly that figure, with no "default" badge.

## Scenario: a team manager edits the calendar, a regular user only reads it

1. The throwaway regular user signs in and opens the golden sprint's capacity page by its deep link.
   - *Expected*: both cards say only the team's manager or an administrator can edit; **Save calendar**, **Save default**, the
     team default, the team coefficient, every day cell and every checkbox are disabled; there is no **Coefficient** column
     (the server withholds per-person coefficients from a caller who cannot edit).
2. The administrator signs in, opens the team, clicks **Edit**, picks the other throwaway user in the **Manager** picker and Saves.
   - *Expected*: the team's page reads "Manager: <the user's name>".
3. The manager signs in and opens the same page.
   - *Expected*: no read-only note; the **Coefficient** column is there.
4. They set the team default to 55 and **Save default**, then set the first open day cell to 0.25 and **Save calendar**.
5. They reload the page.
   - *Expected*: the team default reads 55 and the edited cell reads 0.25.

## Not covered here (and why)

- The grid mechanics (toggles, the typed-number handling, the exact totals against hand computations, `403`/`409` messages) are
  unit-tested in `web/src/components/SprintCalendarGrid.test.tsx` and `web/src/pages/Capacity.test.tsx`; the unsaved-edit handling (a refetch under a dirty draft, the discard confirm on the pickers, the post-save re-read of "Capacity in reports") in `web/src/pages/CapacityEdits.test.tsx`; the page's pickers-only
  state is scanned by `accessibility.spec.ts`.
- The server-side rules (who may write, the validation, DERIVE's precedence) are covered by the server suite.
