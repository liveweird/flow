# Reports (v0.3.0 + the v0.4.0 Deep dive — the non-admin journey: batch 1 M4 commit 14, batch 2 M5 commit 19, checkup A15)

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
   the team's roster, so Costs has a real author's man-days to read.
3. It creates the regular user, then polls the report API (bounded, ~240s each) until the team's
   commitments list the golden sprint — the proof that a DERIVE has run under the mapping — and
   until the team's Costs report counts man-days — the proof that a DERIVE has read the
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
reads a team. The epic reports (Epic estimation accuracy, Estimate adjustments) read a year-long
window (`EPIC_WINDOW`: from 365 days before the reference date) instead, because the stub's epics all
finished in autumn 2025, before the six-month window opens.

Every report's filter bar is collapsible and starts folded; a scenario that drives a filter control
unfolds it first (`openFilters`, which leaves an already unfolded bar alone).

**Dispatch and locks**: the file is the first thing the runner starts (the `first` project, `playwright.config.ts`: it is the longest chain), and it holds
all four Playwright locks of `REPORTS_EXCLUSIVE_LOCKS` (`helpers.ts`), so it never runs beside `capacity`, `data-sources`, `public-holidays` or `metrics-config`:
each keeps a live synced connection, and a holiday or a settings edit moves the derive inputs; a DERIVE of theirs finishing under a test's feet moves the
team-level Costs, Throughput and Home figures it reads and drills (its team's tasks are credited by the roster across every connection derived since).
Between steps 2 and 3 of the setup the shared read-only fixture is prepared and paused (`sharedFixtureReady`, a no-op once another spec did it), so it
cannot be re-derived after the team has its roster person.

## Scenario: a regular user reads the golden sprint's commitments

1. The regular user signs in and opens **Delivery** from the sidebar.
   - *Expected*: the **Commitments** report loads (there is no admin gate on reports).
2. They pick the seeded team, the period "One sprint" and the sprint "FLO Sprint 4".
   - *Expected*: the URL carries `sprintId` and `teamId` (the page starts on a team of its own —
     commitments never read the whole unit); a "Derived … · configuration revision N" line and the
     initial-vs-final chart are shown.
3. They read the sprint's row.
   - *Expected*: sprint name, the team, and **Initial** = the golden committed MD and items,
     **Final** = the golden final MD and items; no drift badge (nothing moved since the sprint
     closed); the table also has **Capacity (MD)** and **Load** columns.
4. They read the by-epic section under the chart ("Initial and final scope per sprint, by epic").
   - *Expected*: a stacked chart ("Chart: initial and final scope by sprint, stacked by epic") and a
     table of one row per epic segment of the sprint (Sprint · Epic · Initial (MD) · Initial (items) ·
     Final (MD) · Final (items)); each figure column adds up to the golden sprint's own figure.
     They press the biggest segment's **Initial (items)** figure.
   - *Expected*: a dialog "Committed — <epic> · FLO Sprint 4" opens, the URL carries the epic segment
     (`drill=sprint:<teamId>:<id>:COMMITTED:e=…`), its summary line is exactly that row's items and
     man-days with one listed task per item and no "data changed" note; Escape closes it and
     focus is back on the figure.
5. They open the report's old address, `/reports/velocity?lastSprints=3`.
   - *Expected*: they land on `/reports/commitments` with `lastSprints=3` still in the URL and the
     **Commitments** heading shown.

## Scenario: the user regroups throughput by month

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Throughput**
   tab.
   - *Expected*: the URL is the throughput route still carrying the sprint and team (the tab
     switch keeps the filter).
2. They read the sprint view.
   - *Expected*: the golden sprint's row shows the golden delivered MD and items, and beside them its
     **Actual cost (MD)** (the time logged inside the sprint's dates on its items). When that figure is
     above zero, pressing it opens "Actual cost · <sprint>" whose summary (items · MD) carries exactly
     that figure, listing one task per row with the MD logged on it (adding up to the figure); Escape
     closes it, focus back on the figure. A zero figure is plain text.
3. They read the by-epic section ("Delivered per sprint, by epic"), which belongs to the sprint view
   only.
   - *Expected*: a stacked chart ("Chart: delivered scope by sprint, stacked by epic") and a table of
     one row per epic segment (Sprint · Epic · Delivered (MD) · Delivered (items)) whose figures add
     up to the golden delivered MD and items. Pressing the biggest segment's **Delivered (items)**
     figure opens "Delivered — <epic> · FLO Sprint 4" with exactly that row's items and man-days
     listed; Escape closes it, focus back on the figure.
4. They read the period view, grouped by week (the default).
   - *Expected*: a "Delivered in the period: N MD (M items). Actual cost: C MD" summary; the period
     table's first column reads "Week starting", with one dated row per week across the sprint's
     window (more than one) and an **Actual cost (MD)** column. The week that logged the most time
     has a figure button: it opens "Actual cost · <week>" whose summary carries exactly that figure,
     the listed tasks' MD adding up to it; Escape closes it, focus back on the figure.
5. They change **Group by** to **Month**.
   - *Expected*: the URL gains `bucket=MONTH`; the table's first column now reads "Month" and
     holds one `YYYY-MM` row (the whole window falls in one month); the chart is still shown.

## Scenario: a user switches throughput to the cumulative view

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Throughput**
   tab.
   - *Expected*: the **Chart view** switch reads **Per period** (selected), the URL carries no
     `view`, and both charts — delivered scope by period and by sprint — are shown.
2. They choose **Cumulative**.
   - *Expected*: the URL gains `view=cumulative`; both charts become the cumulative ones (running
     totals as stacked areas and a dashed cost line); the period table gains **Cumulative (MD)**,
     **Cumulative (items)** and **Actual cost, cumulative (MD)** columns and its last week's running
     totals equal the "Delivered in the period" delivered and cost totals; the golden sprint's row
     shows its own delivered MD and its own cost as its running totals.
3. They choose **Per period** again.
   - *Expected*: `view` leaves the URL and the per-period charts return.
4. They choose **Cumulative** once more and open the **Commitments** tab.
   - *Expected*: the URL no longer carries `view` (it is no filter, so it follows no other report).

## Scenario: a user folds and unfolds the report filters and the choice is remembered

1. The regular user signs in and opens **Delivery** (Commitments).
   - *Expected*: the filter bar is folded — a "Filters" toggle (`aria-expanded` false, no badge)
     and no Period control; the URL already carries a `teamId` (commitments require a team, and a
     required team is not a narrowing, so it never counts on the badge).
2. They unfold it and pick the period "Last 30 days".
   - *Expected*: `aria-expanded` is true and the controls show; the URL carries `from` and `to`;
     the toggle's badge reads **1** (one filter differs from the default).
3. They fold it again.
   - *Expected*: the controls are gone, the badge still reads **1**.
4. They reload the page, then unfold it and reload again.
   - *Expected*: the bar stays folded after the first reload and unfolded after the second (the
     choice is remembered per report).
5. They open the **Throughput** tab.
   - *Expected*: its own filter bar is still folded (each report remembers its own state).

## Scenario: a user's filters are remembered when they come back

1. The regular user signs in, opens **Delivery** (Commitments), unfolds the filter bar, picks the
   spec's own team (the page starts on the first team by name), the period "Last 30 days" and
   the stub person under **Member**.
   - *Expected*: the URL carries `from`, `to`, the `teamId` and the person's `accountId`.
2. They go to **Home** through the nav, then back to **Delivery** through the nav (a bare
   address).
   - *Expected*: the page restores what they left: the URL is rewritten (replace) to the very
     same query string, the Period control reads "Last 30 days" and the folded toggle's badge
     reads **2** (the period and the person; the required team never counts).
3. They open `/reports/commitments?lastSprints=3` as a link.
   - *Expected*: the link is taken as written — the URL keeps `lastSprints=3` and carries no
     `from`.
4. They go to **Home** and back to **Delivery** through the nav again.
   - *Expected*: the link did not replace the memory — the URL is again the "Last 30 days" plus
     person query string from step 1.

## Scenario: the user reads the golden sprint's scope movement

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Sprint
   consistency** tab.
   - *Expected*: the committed/delivered and the final-scope-partition charts are shown.
2. They read the "All figures" table's row for the sprint.
   - *Expected*: committed, added, removed, final, delivered, carried over and dropped each show
     the golden `MD (items)` pair — the A17 partition the report exists to make visible.
3. They look at the filter bar.
   - *Expected*: the URL names the report's team, the Team control is there and there is no
     Member control (the report always covers every member of the team).
4. They pick Activity type **Bug**.
   - *Expected*: the URL gains `activityType=Bug`, a note says the figures are sliced and live, the
     sprint's committed figure changes, and there is no drift badge.
5. They clear Activity type.
   - *Expected*: the URL drops `activityType`, the note is gone and the golden committed figure
     is back.

## Scenario: the user reads cycle time

1. The user opens **Delivery**, narrows to the golden sprint, and switches to the **Cycle time**
   tab.
   - *Expected*: both the "Working days" and "Elapsed days" views are shown, each with its own
     "Of N finished in this period" accounting, and each either a percentile strip or the
     counts-only minimum-sample notice.
   - *Expected*: the per-period trend table is present (the team finished work in the window).
   - *Expected*: the URL carries the picked team (cycle time is always read for one team).
2. They open the same report over a year-long window (deep link carrying the spec's own team), which
   holds the stub's finished epics.
   - *Expected*: an "Epics" card shows "Epics: working days" and "Epics: elapsed days", each with
     its own accounting; with the tasks' two, every accounting adds up.
   - *Expected*: at team level the epics card stays and there is no "Epics by owner team" table
     (epics split by owner team, never by person).
3. They open the **Team** filter.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option.
4. They pick the seeded team's roster person in the **Member** filter.
   - *Expected*: the URL gains `accountId` and keeps the team; the "By member" table is replaced by a
     "Totals per person" table with one row naming the person; the epics histograms give way to the
     "not per person" note. (The seeded team has one stub person, who may have too few finished
     tasks for a histogram, so the "1." numbering and the two-person stacking are covered by the
     page's unit tests only.)

## Scenario: a user widens the cycle time histogram's long tail

1. The user opens the **Cycle time** report for this spec's connection over the default fixed window
   (deep link carrying the spec's own team).
   - *Expected*: the "Long tail" control reads 5% and the URL has no `tailPercent`; the "Elapsed
     days" histogram table already ends in an open row "≥ X (long tail)".
2. They set the long tail to 25%.
   - *Expected*: the URL gains `tailPercent=25`; the last histogram row is still an open "≥ X (long
     tail)" bucket, now starting at a different (lower) value.
3. They set it to 0%.
   - *Expected*: the URL has `tailPercent=0` and the last histogram row is a closed range — no "≥" row.
4. They set it back to 5%.
   - *Expected*: `tailPercent` leaves the URL and the "≥ X" row is back.

## Scenario: the user prints a report and jumps back to the top

1. The user opens the **Cycle time** report for this spec's connection (deep link carrying the spec's own team); the browser's
   print is stubbed so the call can be counted.
   - *Expected*: the report's print header ("Flow · Cycle time") is not visible on screen.
2. They click **Print / save as PDF**.
   - *Expected*: the browser's print was called once.
3. The page is switched to print media.
   - *Expected*: the print header is visible — the report name, a "Filters: Period: … Team: <the spec's team>" line and a
     "Generated <date> <time> (<zone>)" line — while the navigation link, the **Filters** toggle and the print button are
     hidden; the page heading stays.
4. They return to screen media and press End.
   - *Expected*: no **Back to top** button at the top of the page; it appears once the page is scrolled down.
5. They click **Back to top**.
   - *Expected*: the page is back at the top (`scrollY` 0), focus is on the report's heading, and the button is gone again.

## Scenario: the user reads task estimation accuracy

1. The user opens **Estimation** from the sidebar.
   - *Expected*: the **Task estimation accuracy** report loads.
2. They narrow to the golden sprint.
   - *Expected*: the "Against the estimate at start" (primary) and "Against the estimate at done"
     views are both shown, each followed by its "Left out of this distribution" accounting, and
     each either a percentile strip or the counts-only notice. A shown view's strip has the
     median, the mean and the items and no p90 or p95, and sits with an "Accuracy measures"
     table of nine rows; a view below the minimum sample has neither. The URL carries the picked
     team (task accuracy is always read for one team).
3. They open the **Team** filter.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option.
4. They pick the seeded team's roster person in the **Member** filter.
   - *Expected*: the URL gains `accountId` and keeps the team; a "Totals per person" table lists
     that person in one row; each shown view still has its strip without p90/p95 and its measures
     table. (The seeded team has one stub person, who may have too few finished tasks for a
     histogram, so the "1." numbering and the two-person stacking are covered by the page's unit
     tests only.)

## Scenario: the user reads the distribution of task estimates

1. The user opens **Estimation** from the sidebar and switches to the **Task estimates** tab (the last one), then
   narrows to the golden sprint.
   - *Expected*: the URL is `/reports/task-estimates` with the connection and the picked team; the heading
     "Task estimates" and two histograms — "Estimate at done" first, then "Estimate at start" — each followed
     by its own "Left out of this distribution" accounting ("Of N finished in this period:", the reasons "No
     estimate at done" under the first, "Never started, so no estimate at start" and "No estimate at start
     (including estimated late)" under the second) whose closing equation adds up.
   - *Expected*: each histogram is either the FULL percentile strip (median, p90, p95, mean and items) or the
     counts-only notice; there is no "Accuracy measures" table (this report measures estimates, not ratios).
2. They open the **Team** filter.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option.
3. They pick the seeded team's roster person in the **Member** filter.
   - *Expected*: the URL gains `accountId` and keeps the team; a "Totals per person" table lists that person in
     one row; each histogram still has its full strip or notice. (The seeded team has one stub person, so the
     "1." numbering and the two-person stacking are covered by the page's unit tests only.)
4. They open the report over a year-long window (deep link carrying the spec's own team), where the team has
   enough finished tasks for a histogram, and use the **Long tail** control on the "Estimate at done" histogram.
   - *Expected*: the histogram's first column is headed "Up to (MD)" (the fixed 0.5-MD bars are named by their
     upper bound, not a "from – to" range). The control reads 5% and the URL has no `tailPercent`; each last row
     is a plain value ("1.5") or the open tail "> X (long tail)" (the estimates strictly above X — estimates are
     discrete, so ties at the top may swallow the open row, unlike cycle time's continuous days).
   - Setting 25% puts `tailPercent=25` in the URL; setting 0% puts `tailPercent=0` and the last row is a plain
     value (no open row at all); setting 5% again removes the param.

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
     lists the six stages (Not started, In progress, Waiting, Blocked, Done, Unmapped status).
2. They change **Count by** to **Status**.
   - *Expected*: the URL gains `by=STATUS`; the caption says the bands are by status; the table now
     lists the workflow's own statuses (To Do, In Progress, Done) and no longer the "Not started"
     stage; the chart is still shown.
3. They open the **Team** control.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option (WIP is read
     for one team or one domain, never the whole unit).

## Scenario: the user reads one person's work in progress inside the team

1. The user opens the **WIP** report for the seeded team (deep link, fixed window), unfolds the filters and
   picks the team's stub person in **Member**.
   - *Expected*: the URL gains `accountId` and keeps the team; the request the page makes names the account and the
     answer is a USER-level report with a non-empty daily series whose every point carries that one person in `byUser`
     (labelled with their name).
2. They read the page.
   - *Expected*: a note says it counts the tasks the picked people hold at the end of each day; the stacked-bands chart
     and the per-band table (with an "In progress" row) are shown; there is no "By person" view switch for one person.

## Scenario: the user reads aging work in progress and blocked time

1. The user opens **WIP** for the seeded team and switches to the **Aging WIP** tab.
   - *Expected*: the URL is the aging route and still carries the team; the Tasks thresholds tile
     row shows p50, p85 and p95 (the team finished plenty); the "Open work, oldest first" table has
     its "Age (working days)" column and at least one item, keyed like `FLO-12`.
2. They switch to the **Blocked time** tab.
   - *Expected*: the "Blocked working days" and "Blocked share of cycle" views are both shown, each
     a percentile strip or the counts-only notice, with the "N of M items were never blocked"
     line and the "Most blocked items" block.

## Scenario: the user reads the aging chart with the team's board columns on its workflow axis

1. The user opens **Aging WIP** for the seeded team (the FLO board is mapped to it).
   - *Expected*: the "Aging chart" card is shown above the "Open work, oldest first" table; its x axis
     is titled "Workflow position" and lists the team's board columns (the report's `positions` are all
     of kind COLUMN, every one printed as an axis label); its y axis is titled "Age (working days)";
     there is one dot per task, and the table below has one row per listed task (the page shows tasks
     by default; epics are behind the page's Tasks | Epics switch).
2. When the list holds epics, they pick **Epics** in the page's Tasks | Epics switch.
   - *Expected*: the URL gains `kind=epics`, the table has one row per listed epic, and picking
     **Tasks** again drops `kind` from the URL and brings the tasks back.
3. They open the same report with no team in the link, and unfold the filters.
   - *Expected*: the page is never read for the whole unit: the URL gains a `teamId`, the chart is still shown with
     its axis title, the **Team** control shows a team and has no clear button.

## Scenario: the user drills from a domain into the golden epic's progress

1. The user opens **Epic progress** for the FLO domain (deep link, fixed window; the page is never read for the whole unit).
   - *Expected*: the card reads "Domain: FLO" and shows the PV, EV, AC, SPI and CPI tiles; the URL keeps
     the connection and the domain; the Epics table lists the golden epic.
2. They look at the Epics table.
   - *Expected*: the golden epic has a link in it.
3. They follow the golden epic's link (FLO-33).
   - *Expected*: the URL gains `epicId=FLO-33`; the card is titled with the epic; the breadcrumb
     offers the way back through "FLO"; the PV tile equals the epic's budget (the plan is fully
     spent long before the window ends); the Plan panel shows the budget and the planned start and
     due dates — all three from `expected.json`'s `golden.epic`.
4. They open the daily figures under the chart.
   - *Expected*: the chart's text alternative lists the days newest first, ISO dates, one row per
     day of the window (more than a hundred).

## Scenario: the user reads a domain's progress over the period and its weekly SPI and CPI trend

1. The user opens **Epic progress** for the FLO domain (deep link, fixed window).
   - *Expected*: the card reads "Domain: FLO"; the "Measured over" control has "This period" selected
     and the URL carries no `basis` (the server's per-level default); the chart card is titled
     "PV, EV and AC in the period" and the PV tile shows "Since start: …" as its small second line.
2. They read the trend card.
   - *Expected*: "SPI and CPI trend" has its index chart and, beside it, the weekly table — newest week
     first, ISO dates, one row per week of the half-year window (more than twenty).
3. They switch to "Since start".
   - *Expected*: the URL gains `basis=CUMULATIVE`; the chart card is titled "Cumulative PV, EV and AC"
     and the PV tile now shows "This period: …" as its second line.

## Scenario: the user reads the data-quality findings

1. The user opens **Data quality** for the seeded team (deep link, fixed window; the page is never read for the whole unit).
   - *Expected*: the overview names what was checked (done tasks, …) and its headline tiles.
2. They compare three findings with their cards: work logged more than a day late, authors without a team, and
   work done outside any sprint.
   - *Expected*: the late-logging tile is above zero (the stub guarantees it); each card states the same count
     as its tile — "Found: N", or "None found" for a zero; the "Authors without a team" card says that this
     belongs to no team, so a single team's view lists none.
3. They follow the "Authors without a team" tile.
   - *Expected*: focus lands on that card and the URL is unchanged (the tile writes no hash).
4. They read the configuration findings.
   - *Expected*: "Boards without a team" has findings, and the page has NO "Open the metrics
     configuration of …" link — that admin shortcut is for administrators only.

## Scenario: the user reads the costs of a team, and the old Cost matrix address lands on them

1. The user opens **Costs** for the seeded team (deep link, fixed window; the page is never read for the whole unit).
   - *Expected*: the page title reads "Costs" and the card "Man-days logged by the team"; the "Man-days logged" tile is above zero
     (the roster person logged work), "Foreign work (MD)" is no more than it, and "Foreign work share" is shown. The retired
     matrix ("Man-days by author and domain") and epic table ("Man-days by epic and author") are not on the page.
2. They read the two breakdowns, "Man-days by activity type" and "Man-days by work category".
   - *Expected*: each lists at least one slice with its man-days and share, and the figures add up to the
     grand total (each is rounded once, so within 0.005 a slice). They press the largest activity-type
     figure: a drawer "… · Activity type" lists the tasks of that slice, its summary line reading the same
     man-days as the figure and the table having an "Authors" column; the X closes it.
3. They open the old address `/reports/cost-matrix` with the same connection, window and team.
   - *Expected*: the browser lands on `/reports/costs` with the query kept (the team is still in the URL), the page is titled "Costs"
     and the "Man-days logged" tile is shown.

## Scenario: the user reads a sprint's actual cost by epic and against capacity on Costs, and drills it

1. The regular (non-admin) user opens **Costs** for the seeded team narrowed to the golden sprint (deep link).
   - *Expected*: the "Actual cost against capacity, per sprint" card is there for this user, whatever their role: its table
     "Actual cost and capacity per sprint" has the "Capacity (MD)" and "Capacity source" columns and one row for the golden sprint.
     (The capacity figure depends on the team's calendars, so only its presence is pinned.)
2. They read the by-epic card "Actual cost per sprint, by epic" and its table "Actual cost per sprint and epic".
   - *Expected*: the sprint's actual cost is above zero (the roster person logged work inside its dates) and the segments' man-days
     add up to it (each is rounded once, so within 0.005 a segment).
3. They press the sprint's actual-cost figure in the capacity table.
   - *Expected*: a drawer "Actual cost · <golden sprint>" opens (the URL carries `drill=cost-sprint…`), its summary reads exactly that
     figure, with no "data changed" note; Escape closes it and focus is back on the figure.
4. They press the first segment's figure in the by-epic table.
   - *Expected*: a drawer "Actual cost — <epic> · <golden sprint>" lists only that epic's worklogs, its summary reading exactly
     the segment's figure; Escape closes it and focus is back on the figure.

## Scenario: the user compares a team's work-category mix with its targets on Costs and reads the weekly share trend

1. The regular (non-admin) user opens **Costs** for the seeded team (deep link, fixed window); the team has no work-category targets yet.
   - *Expected*: the card "Work-category targets against the actual mix" says "<team> has no work-category targets yet." and links to
     `/teams/<id>/work-category-targets`; there is no targets table.
2. They read the card "Work-category share, cumulative by week" and its table "Cumulative share of each work category by week".
   - *Expected*: the table has a "(uncategorized)" column (the stub maps no work category, so all the time is uncategorized); its newest row is a
     week's Monday (`YYYY-MM-DD`) whose "Logged so far (MD)" is the page's grand total (within 0.005) and whose "(uncategorized)" share is 100%.
3. An administrator (through the API) targets 100 % on the uncategorized time, and the user reloads the page.
   - *Expected*: the table "Work-category targets and actual mix" has the columns Category, Target %, Actual MD, Actual %, Δ (pp), and one
     "(uncategorized)" row reading target 100, the grand total of man-days, 100% and a difference of 0.0; the "no targets yet" note is gone. The targets
     are cleared again afterwards (this spec's own team).

## Scenario: the user reads the overview of the picked teams on Home and follows a section into its report

1. The user signs in and pins Home to the seeded team (the remembered pick `flow.viewSettings.home.teams`, so the shared stack's other teams cannot
   push it out of Home's three), then reads Home, the overview of the picked teams.
   - *Expected*: the freshness line ("How fresh the data is") and the seven sections — Last sprint, How big the work is, How well
     estimates hold, Epics in progress, Tasks in progress, How much is delivered, What is missing from the data — are shown (one
     request answers the page), each with its title as a link.
2. They look through the charts' visually hidden tables.
   - *Expected*: the seeded team is listed in each (its last closed sprint row, the three granularity medians, the epics' and tasks'
     counts and ages, the cycle time percentiles) and has its own accuracy and weekly-delivery table — all attached to the page,
     none shown (a keyboard user reveals one by tabbing into it). The **What is missing from the data** section has no chart: its
     one table of counts ("Findings per team", a row per team, a column per finding) is shown, with a row for the seeded team.
3. They read the weekly-delivery heading's team link.
   - *Expected*: it opens the Throughput report for that team and the window of the figures (`teamId`, `from` and `to`).
4. They follow the **How big the work is** section's title.
   - *Expected*: the Cycle time report opens with the section's own period in the URL (`from`/`to` dates) — a section link is never
     bare, since a bare link would apply the remembered team.
5. They go back Home and follow the **Last sprint** section's title.
   - *Expected*: the Commitments report opens with `lastSprints=1` in the URL.

## Scenario: the user picks which teams Home shows and the choice survives a reload

Home lists at most three teams; with nothing remembered a viewer who manages none sees the first three by name. The test creates a team whose name
sorts first so it is in that default, and reads the visible "Findings per team" table (one row per listed team).

1. An admin creates the throwaway team through the API; a regular user signs in and lands on Home.
   - *Expected*: the throwaway team has a row in **Findings per team**, the **Teams shown** picker is there, and there is no
     **Back to my default teams** button (nothing is remembered).
2. They make the seeded team the only pick: remove the throwaway team's pill, add the seeded team from the dropdown if it is not among the
   default teams, then remove every other pill (the last pill is never removed — an empty picker means the default).
   - *Expected*: the seeded team has a row, the throwaway team has none, and **Back to my default teams** is shown.
3. They reload the page.
   - *Expected*: the same pick comes back — the seeded team's row, no row for the throwaway team, the button shown.
4. They press **Back to my default teams**.
   - *Expected*: the throwaway team is listed again and the button is gone.
5. The throwaway team is deleted through the API (also when a step fails).

## Scenario: the keyboard reveals a Home table on focus, one Tab stop per table, and the trend arrows stay inside their charts

The unit tests cannot see the CSS: this scenario pins the visually hidden tables' reveal and tab stops in a real browser.

1. The user signs in, lands on Home and looks at the "Last sprint" section's table of figures.
   - *Expected*: the table is in the page but clipped to a 1 px box; exactly one of its figure buttons is a tab stop (the roving
     tab stop), though the team's row has several.
2. They focus the first figure.
   - *Expected*: the table's box opens to its full width and the figure is in the viewport.
3. They press the right arrow, then Tab fifteen times.
   - *Expected*: the arrow moves focus to the row's next figure; Tab never lands in that table again (its other figures are not tab
     stops), reaches another hidden table, and the first table closes again once focus has left it.
4. They scroll to "How much is delivered" and read the cycle time p50 chart.
   - *Expected*: every trend arrow (↑ ↓ →) drawn beside a bar lies inside the chart's SVG box — none is clipped at the right edge.

## Scenario: the user reads epic estimation accuracy against each epic's own estimate

Epic estimation accuracy is read for one team (the epic's owner team), so every deep link carries the
spec's own team.

1. The user opens **Epic estimation accuracy** for this spec's connection over the default fixed
   window (deep link with the team).
   - *Expected*: the report loads and, since the stub's epics all finished before that window
     opens, shows the "No data in this period" empty state — no "Finished epics" table.
2. They open the same report over the year-long window (`EPIC_WINDOW`, from 365 days before the
   reference date), where the stub's epics finished.
   - *Expected*: the "Against the own estimate at start" and "… at done" views are both shown, each
     with its own "Left out of this distribution" accounting, and each accounting's closing "n +
     reasons = population" line adds up; a "Derived … · configuration revision N" line is shown. Each
     view shows either a centre strip (the median, the mean and the items — no p90 or p95) and its
     nine-row "Accuracy measures" table, or the minimum-sample notice ("Only N items in this
     selection …") with neither — and every view is one of the two (the seeded team's few epics may
     leave either or both under the minimum sample).
3. They read the golden epic's row (`golden.epic`) in the "Finished epics" table.
   - *Expected*: there is one row per epic in the population; the own estimate at start and at done
     equal the golden budget, the child sum is the golden child sum, and the ratio is actual ÷ the
     OWN estimate — not actual ÷ the child sum. No other team's (GTM, OPS, PLT) epic is listed.
4. They narrow the **Domain** to GTM, then clear it.
   - *Expected*: the URL gains `domain=GTM` and, the seeded team owning no GTM epic, the report
     shows "No data in this period" with no "Finished epics" table; after the clear the golden FLO
     epic is back.
5. They open the **Team** filter.
   - *Expected*: the URL carries the seeded team, the filter shows it, has no clear button and
     offers no "All teams" option, and there is no **Member** control on the page.

## Scenario: the user reads how estimates were adjusted

1. The user opens **Estimate adjustments** over the year-long window (deep link carrying the spec's own team).
   - *Expected*: the report loads with the note that the domain view applies to tasks while epics
     read their own space; the URL carries the picked team (the report is always read for one team).
2. They read the "Tasks" and "Epics" blocks.
   - *Expected*: each block shows started, changed after start (at most started) and estimated late
     (at most changed); each has a "Change from start to done" percentile strip or the counts-only
     minimum-sample notice (the seeded team is small), a "Not charted: N finished items with an unchanged
     estimate" line when there are any (the histogram charts only the items whose estimate changed), and its accounting
     line adds up (the unchanged items are one of its terms); a "By member" table lists the team's
     people.
3. They narrow **Activity type** to Bug, then clear it.
   - *Expected*: the report is requested again with `activityType=Bug` and the URL gains it; the tasks
     started figure is at most the unfiltered figure; after the clear the URL loses the parameter and
     the tasks started figure is back to the unfiltered figure.
4. They open the **Team** filter.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option.
5. They pick the roster person in the **Member** dropdown.
   - *Expected*: the URL gains `accountId` and keeps the team; the "By member" table is replaced by a
     "Totals per person" table with one row naming the person; the Epics block is replaced by the note
     that epics are not attributed to individual people; the Tasks block is still shown. (The seeded
     team has one stub person, who may have too few changes for a histogram, so the "1." numbering
     and the two-person stacking are covered by the page's unit tests only.)

## Scenario: the user reads reported time beside flow efficiency

1. The user opens Epic estimation accuracy (deep link, carrying the spec's own team) and switches to the **Reported time** tab.
   - *Expected*: the URL is the `reported-time-ratio` route and still carries the spec's connection
     and team (reported time is always read for one team); the **Reported time** report loads.
2. They read the two views.
   - *Expected*: "Reported time ÷ cycle time" and "Flow efficiency" are both shown, each a percentile
     strip with its own accounting (the partitions differ) that adds up; the very-short-cycles
     outlier note is shown; the ratio's histogram is available as a table.
3. They narrow **Domain** to FLO, then clear it.
   - *Expected*: the URL gains `domain=FLO` and the population ("Of N finished in this period")
     does not grow (the team's work is mostly FLO's); after the clear it is back.
4. They switch the **Domain view** to "Earned in".
   - *Expected*: the URL gains `domainView=EPIC` and the report still renders.
5. They open the **Team** filter.
   - *Expected*: it shows the seeded team, has no clear button and offers no "All teams" option; the
     team's members are the "By member" table.
6. They pick the seeded team's roster person in the **Member** filter.
   - *Expected*: the URL gains `accountId` and keeps the team; the "By member" table is replaced by a
     "Totals per person" table with one row, the person's name (prefixed "1." when the distributions
     are shown); the Flow efficiency histogram table gains a "1." column for that person, or — when the
     person's sample is below the minimum sample size, as the small stub roster may be — the "Only N
     items in this selection" note stands in for it; the person's finished work is a part
     of the team's. (The seeded team has one stub person, so two-person stacking is covered by the
     page's unit tests only.)

## Scenario: the user reads the estimated backlog and what it means in sprints

1. The user opens **Estimated backlog** for the seeded team (deep link, fixed window, with no staleness limit — `touchedWithinDays=0` — since the stub's dated history would fall outside the default 90 days).
   - *Expected*: the card is titled "Backlog on" its as-of day (the newest derived day inside the window, never after its end); the Backlog (MD) and Items
     tiles hold positive figures and the "Backlog in sprints" tile reads "≈ N sprints ahead" with
     its "Recent pace: … the mean of N closed sprints" basis.
2. They open the daily figures under the trend chart.
   - *Expected*: the chart and its text alternative are shown; the table's newest row is the
     as-of day and equals the tiles' man-days and items; the table has a row per day of the
     window.
3. They unfold the filters and look at the **Team** control.
   - *Expected*: the page is never read for the whole unit: the control has no clear button and offers
     no "All teams" option.
4. They pick the **Domain** FLO.
   - *Expected*: the URL gains `domain=FLO`; the sprints tile shows a dash and says the selection has
     no velocity of its own rather than inventing a pace.
5. They pick the seeded team.
   - *Expected*: the URL gains the team and loses the domain — there is no team × domain split.
6. They open the page again with a member pasted into the link (`accountId=nobody`) and open the filters.
   - *Expected*: the URL loses the account (the backlog is a team's, a member is dropped); the filter bar offers
     the **Team** control and no **Member** control.
7. They move the **Last change within** slider: it starts on "90 days (default)" with no `touchedWithinDays` in the URL; they press End, then Home.
   - *Expected*: End reads "No limit" (the URL gains `touchedWithinDays=0`, the Items tile has a positive figure and no
     "hidden" note); Home reads "7 days" (the URL gains `touchedWithinDays=7`) and the Items tile names the window ("N items (X MD) hidden: no
     change in the last 7 days.", or "Nothing hidden" when none); the Items figure is at most the unlimited one and plus the hidden N it
     equals it exactly.
8. They open the daily figures at 7 days.
   - *Expected*: the table's newest row equals the tiles' man-days and items — the per-day filter and the current figures agree.

## Scenario: the user dives into the golden sprint and drills an epic to its tasks

1. The user signs in and follows **Deep dive** in the sidebar.
   - *Expected*: the page loads with no selection in the address and shows the explainer of the two ways
     in ("Pick something to dive into") — nothing has been requested.
2. In **Sprints of a domain** they pick the domain FLO and the sprint "FLO Sprint 4" and press **Show**. There is no connection picker:
   the sprint option is labelled with its dates ("FLO Sprint 4 · from → to") and, when the stack holds several connections, with its
   connection's name — they take this spec's own.
   - *Expected*: the URL is the canonical selection, `?domain=FLO&sprintId=<golden>&connectionId=<this
     spec's>`; the matrix grid is drawn with the three layer switches on (Plan (PV), Approximate EV, Cost
     (AC)), a bar of each layer and a done marker (◆) somewhere in it, and the legend beneath.
   - *Expected*: the Selection panel has folded to a one-line summary ("<domain> · Sprints: FLO Sprint 4 …") and an **Edit selection**
     button reporting `aria-expanded="false"` and holding keyboard focus (the **Show** button it replaced); the form (and **Show**) is gone.
3. They open the epic FLO-36 by its row toggle.
   - *Expected*: the toggle reports itself expanded and the epic's tasks (FLO-52 among them) are rows
     under it.
4. They open the month column into weeks.
   - *Expected*: keyboard focus lands on the first week's header (a button offering that week's days);
     the month stays as a "Collapse <month>" control above its weeks.
5. They move keyboard focus onto a FLO-36 cell that holds logged time.
   - *Expected*: the cell's tooltip opens without the pointer and shows its Plan (PV) and Cost (AC)
     figures as headed sections and the authors as rows, each name with its man-days on the right
     ("Sample User N" … "x MD").
6. They read the computed styles of the drawn marks (what the unit tests cannot compute).
   - *Expected*: a matrix cell has non-zero `scroll-margin-left` and `scroll-margin-top` (a focused cell
     scrolls clear of the sticky item column and header rows); a row toggle has a `scroll-margin-top` and
     a column toggle a `scroll-margin-left`; a bar has a `box-shadow` (its halo in the surface colour),
     the ◆ a `text-shadow`, and the epic window outline a `box-shadow` (its ring); the open tooltip has a
     `max-height` and `overflow: auto`.
7. They switch the **Approximate EV** layer off.
   - *Expected*: every Approximate EV bar, every hollow "worked, nothing earned" mark and every done marker is gone; the plan bars
     are still drawn.
8. They switch to the **Burn-up** tab.
   - *Expected*: the URL gains `view=burnup`; the "Burn-up" heading and the chart (a group named for its
     cumulative plan, earned value, approximate EV and cost) are shown, its legend naming Plan (PV),
     Earned value (EV), Approximate EV and Cost (AC).
9. They open **Show daily figures**.
   - *Expected*: the button reports itself expanded ("Hide daily figures") and the "Cumulative figures per
     day" table appears, newest day first: the first row's day is a date and its plan figure a number, and
     the first row that has earned value and cost (an em dash stands in beyond the day the data is current
     for) holds numbers in all four columns.
10. They switch back to the **Matrix** tab.
   - *Expected*: `view` is gone from the URL, the grid is visible again and the Burn-up heading is gone.
11. They press **Edit selection**, switch to **Epics** (the Domain FLO stays picked and narrows the epic list), pick the golden epic FLO-33 and press **Show**.
   - *Expected*: the form opens first (the same **Edit selection** toggle, now `aria-expanded="true"`); then the URL is `?epicId=<golden epic>&connectionId=<this spec's>`; the epic is a row and
     its planned window is outlined across the columns it spans.

## Scenario: the populated flow, epic and cost pages have no WCAG A/AA violations

1. The user opens WIP (the seeded team), Epic progress (the golden epic) and Costs (the seeded team) by
   deep link, each waiting for its content (the band table, the Plan panel, the activity-type breakdown table).
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included, no waivers) of each
     page reports zero violations — the populated counterpart of `accessibility.spec.ts`, which
     scans the same pages in whatever state the stack happens to be in.

## Scenario: the populated Home overview and data-quality page have no WCAG A/AA violations

1. The user signs in, lands on Home and waits for its seven sections and its first chart to be drawn, then scans it; then focuses a
   figure of the "Last sprint" table (revealing it) and scans again.
   - *Expected*: an axe scan (same tags, no waivers) reports zero violations both times — including the note under the quality
     section, whose link is underlined (a link inside text must not rely on colour), and the revealed table.
2. They open **Data quality** for this spec's connection (deep link, fixed window), wait for the
   findings and scan it.
   - *Expected*: zero violations — including the drift table's orange "reconstructed" badge.

## Not covered here (and why)

- **The Deep dive's figures and keyboard grid** — the journey asserts structure and the browser-computed
  styles (and that the Burn-up's figures are numbers, not their values — the sums, the budget switch and
  the as-of marker are the SPA's `deepDiveBurnup` and component tests'); the matrix's sums, the grid's arrow-key navigation and the tooltip's hover/Escape rules are
  pinned by the SPA's component tests and the server's `DeepDive*Test` classes.
- **The figures of the period views** (throughput's weekly/monthly MD, accuracy ratios, cycle-time
  percentiles) — the journey asserts their presence and shape, not their numbers: they depend on
  the whole stub dataset's worklogs and estimates and are pinned exactly, against independent
  recomputations of the persisted facts, by the server's `Report*Test` classes. The golden sprint's
  figures are the one place the browser journey pins numbers, because they are written into
  `expected.json`.
- **The figures of the estimation and backlog journeys** — epic accuracy's ratios, the adjustment
  shares and medians, reported time's ratio and flow efficiency and the backlog's man-days depend
  on the stub dataset and the clock; they are pinned by the server's `Report*Test` classes. The
  journeys assert structure and self-consistency (accountings add up, a row count equals the
  population, the trend's newest day equals the tiles) and the few figures the generator writes down
  (`golden.epic`'s budget and child sum).
- **Managing the Jira-user roster** — D1 membership editing is covered by `metrics-config.spec.ts`;
  here the roster is one API-seeded person, only so Costs has man-days to read.
- **The figures of the batch-2 pages** — the WIP counts, the aging bands and thresholds, the blocked
  shares, the data-quality counts, the Costs man-days and EVM's SPI/CPI depend on the whole
  stub dataset and the clock; they are pinned against independent recomputations by the server's
  `Report*Test` classes. The journeys assert structure, self-consistency (a tile equals its card, the
  totals add up, a member's sprints add up to the team level's figure) and the few figures the
  generator writes down (`golden.epic`).
- **The empty state** of a period with no data — asserted end to end only where a journey meets it
  for free (epic accuracy's default window); its component is covered by the SPA's
  `ReportChartCard.test.tsx`.
