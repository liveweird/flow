# Reports person trend — one person's figures over time (the non-admin journey)

- **Spec**: [tests/reports-person-trend.spec.ts](../tests/reports-person-trend.spec.ts)
- **Actors**: the seed administrator (`admin@flow.local`), through the API only, to seed, find the person and read the report as the oracle;
  one throwaway REGULAR user (no roles) who does all the reading through the browser — D12, every signed-in user sees every report
- **Owns** (exclusive server-side state): the throwaway reader, created through the API before the block and deleted after it. It READS
  the shared read-only fixture: one Jira-stub data source (`e2e-shared-ds-*`) with a team (`e2e-shared-team-*`, no roster) the stub's FLO
  board is mapped to (the derivation needs a configuration; the person followed needs no team), created by global setup, synced, derived
  and paused by whichever consumer spec gets there first, never mutated, and deleted by global teardown. The person is the stub
  member with the most finished tasks over a year-long window, found through the report's own API. The team-picker test also owns one
  throwaway team (`e2e-trend-team-*`) with one stub person on its roster, created and removed (membership first) inside that test.

## Scenario: a regular user follows one person: a chart per figure with its cue, the numbers behind a chart equal the API window by window, a clean axe scan

1. The user signs in and opens **Person trend** with no one picked.
   - *Expected*: the heading "Person trend", the picker and "Pick a person to see how their figures changed."; the URL has been
     given a period (`from`/`to`, the last 365 days); nothing is requested.
2. They open the deep link of the shared fixture's connection and the window naming the busiest stub person.
   - *Expected*: the URL carries the `accountId` and no team; the Person select shows the person's name; fourteen cards (level-3
     headings) appear — thirteen charts (the two cycle-time percentiles share one) and the "Work mix" — and the "Delivered (items)" chart is drawn.
3. They read the cue under each chart.
   - *Expected*: fourteen cue lines (the cycle chart has one per percentile); each is either an arrow with "Better", "Worse", "No change" or "Too few items to call"
     followed by "first window ending <date>, last ending <date>" and the change, or "No mark: fewer than two full windows with a figure in this range."
4. They open the numbers behind "Delivered (items)" ("Show the numbers").
   - *Expected*: the table "Delivered (items): figures by window" has one row per window of the API's answer, each equal to it: the window's end, the
     figure ("—" for a window with nothing derived), the sample, and the coverage in words ("complete", "partial", "no data derived").
5. They run an accessibility scan.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero violations.

## Scenario: a regular user picks the person through a team: its current members are listed, the pick rides in the URL and survives a reload with the team

1. The test creates a throwaway team (`e2e-trend-team-*`) through the API and puts one stub Jira person on its roster (the shared
   fixture's team has no roster and is never mutated). The user signs in and opens **Person trend** with no one picked.
   - *Expected*: the optional select "Team" is empty.
2. They choose the throwaway team, then open the Person list.
   - *Expected*: without typing, the list holds exactly that team's current member.
3. They pick the member.
   - *Expected*: the URL ends with that `accountId` and carries no team; Person shows the member's name and the report's first card appears.
4. They reload the page.
   - *Expected*: the team is still the throwaway team (remembered per viewer), Person is still the member and the URL still carries the year and the person.
5. They clear the person.
   - *Expected*: the empty state is back and the team stays chosen.
6. The test removes the roster membership, then the team, through the API.
