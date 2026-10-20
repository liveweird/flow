# Reports compare — two people head to head (the non-admin journey)

- **Spec**: [tests/reports-compare.spec.ts](../tests/reports-compare.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed, find the two people and clean up; one
  throwaway REGULAR user (no roles) who does all the reading through the browser — D12, every signed-in user sees every report
- **Owns** (exclusive server-side state): the throwaway reader, created through the API before the block and deleted after it. It READS
  the shared read-only fixture: one Jira-stub data source (`e2e-shared-ds-*`) with a team (`e2e-shared-team-*`, no roster) the stub's FLO
  board is mapped to (the derivation needs a configuration; the people compared need no team), created by global setup, synced, derived
  and paused by whichever consumer spec gets there first, never mutated, and deleted by global teardown. The two people are the stub
  members with the most finished tasks in a year-long window, found through the report itself. The team-picker test also owns one
  throwaway team (`e2e-compare-team-*`) with one stub person on its roster, created and removed (membership first) inside that test.

## Scenario: a regular user compares two people: the table has both, the better figure is tinted teal at most once per row, the cycle-time histogram draws both

1. The user signs in and opens **Compare people** with no one picked.
   - *Expected*: the heading "Compare people", the pickers and "Pick two people to compare them."; person B's picker is disabled
     until A is chosen; nothing is compared.
2. They open the deep link of the shared fixture's connection and the window naming the two busiest stub people.
   - *Expected*: the URL carries both `accountId`s, side A first; the table "Comparison of the two people, metric by metric" has the
     column headers "A · <name>" and "B · <name>".
3. They read the Delivered (items) row.
   - *Expected*: a figure with its sample ("n = …") for each person; the better one's cell also ends with "(better by … items)".
4. They read the table's Difference column and tinted cells.
   - *Expected*: every Difference cell is the plain absolute gap (no sign, no icon, no tint); a figure cell that is the better side of its
     row is tinted teal (never red), has a check icon and ends with its words ("better by …", "closer to 1.0 by …"), and no row has more than one.
5. They read the Working days histogram.
   - *Expected*: its table has a column per person ("1. <name>", "2. <name>") and the chart's legend lists both people; a clean axe scan.

## Scenario: a figure opens the items behind it: the drawer's count is the cell's, Escape closes it and focus returns to the figure

1. The user signs in and opens the same comparison, then focuses A's Delivered (items) figure — a link-styled button named
   "<figure> — A · <name>: show the items of Delivered (items)" — and presses Enter.
   - *Expected*: a dialog named "Delivered tasks · A · <name>" opens and the URL gains `drill=cell:DELIVERED;s=<accountId>`.
2. They read the drawer.
   - *Expected*: the server's summary line starts with the cell's figure, there is no "data changed" note and the items table is shown.
3. They press Escape.
   - *Expected*: the drawer closes, the URL loses `drill` and focus is back on the figure.

## Scenario: a regular user picks person A through a team: its current members are listed, the pick rides in the URL and the team is remembered

1. The test creates a throwaway team (`e2e-compare-team-*`) through the API and puts one stub Jira person on its roster (the shared
   fixture's team has no roster and is never mutated). The user signs in and opens **Compare people** with no one picked.
   - *Expected*: the optional selects "Team (A)" and "Team (B)" are both empty.
2. They choose the throwaway team as Team (A), then open the Person A list.
   - *Expected*: without typing, the list holds exactly that team's current member.
3. They pick the member.
   - *Expected*: the URL ends with that `accountId` and carries no team; Person A shows the member's name.
4. They reload the page.
   - *Expected*: Team (A) is still the throwaway team (remembered per viewer), Team (B) is still empty and Person A is still the member.
5. The test removes the roster membership, then the team, through the API.
