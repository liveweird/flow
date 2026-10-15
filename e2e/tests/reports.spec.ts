// The v0.3.0 REPORTS journey, read the way D12 says everyone reads them: a NON-ADMIN user opens
// the Delivery and Estimation reports over a synced-and-derived Jira-stub connection and finds the
// golden FLO sprint's figures (`sample-data/jira/expected.json` `golden.sprint`) exactly; batch 2
// adds the Flow metrics group (WIP, aging, blocked time, epic progress), Data quality, the Cost
// matrix, Home's unit overview and Commitments' last-N-sprints and member drill; the checkup's A15
// closes the last four pages (epic accuracy, estimate adjustments, reported time, estimated backlog). The
// admin side is API-seeded (a synced stub connection, a team, the FLO board mapped to it — the
// same shape the server's `DerivedStubFixture` uses) so the journeys stay fast; the sync + DERIVE
// wait is a bounded poll of the report API, never a sleep. Owns: its own Jira-stub data source
// (`e2e-reports-ds-*`), team (`e2e-reports-team-*`) with one stub person on its roster, and a
// throwaway regular user, all created through the API before the block and deleted after it.
import { readFileSync } from "node:fs";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import {
  addStubMemberViaApi,
  apiAsAdmin,
  awaitDerivedSprint,
  awaitDerivedTeamCost,
  axeViolations,
  configureMetricsViaApi,
  expect,
  login,
  openFilters,
  stubDayOffset,
  syncStubDataSourceViaApi,
  test,
  uniqueText,
} from "./helpers";

interface GoldenSprint {
  sprintId: number;
  name: string;
  committedMd: number;
  committedItems: number;
  addedMd: number;
  addedItems: number;
  removedMd: number;
  removedItems: number;
  finalMd: number;
  finalItems: number;
  deliveredMd: number;
  deliveredItems: number;
  carriedOverMd: number;
  carriedOverItems: number;
  droppedMd: number;
  droppedItems: number;
}

interface GoldenEpic {
  issueKey: string;
  budgetMd: number;
  childSumMd: number;
  startDate: string;
  dueDate: string;
}

const EXPECTED = JSON.parse(readFileSync(new URL("../../sample-data/jira/expected.json", import.meta.url), "utf8")) as {
  golden: { sprint: GoldenSprint; epic: GoldenEpic };
};
const GOLDEN = EXPECTED.golden.sprint;
const GOLDEN_EPIC = EXPECTED.golden.epic;

/**
 * The period the period-bound batch-2 reports read: the stub's data ends at its reference date, so
 * a fixed window around it keeps them independent of today (the default "last 90 days" would slide
 * off the sample data), passed as `from`/`to` in the URL — the filter IS the URL.
 */
const dayOffset = stubDayOffset;
const WINDOW = `from=${dayOffset(-184)}&to=${dayOffset(7)}`;
/**
 * The stub's epics all FINISHED in autumn 2025, before `WINDOW` opens, so the epic reports (which
 * read epics finished — or started — in the period) read a year-long window instead; the snapshot
 * reports keep `WINDOW`.
 */
const EPIC_WINDOW = `from=${dayOffset(-365)}&to=${dayOffset(7)}`;

/** Man-days the way the SPA writes them (at most two decimals, no trailing zeros). */
const md = (value: number) => String(Math.round(value * 100) / 100);
/** A sprint-consistency cell: "MD (items)". */
const mdItems = (mdValue: number, items: number) => `${md(mdValue)} (${items})`;

let api: APIRequestContext | undefined;
// Assigned as each is created, so a failing beforeAll still lets afterAll remove what exists.
let dataSourceId: number | undefined;
let dataSourceName = "";
let teamId = 0;
let teamName = "";
let memberName = "";
let userId: number | undefined;
let membershipId: number | undefined;
let reader = { email: "", password: "" };

/** Sign in as the regular user and open a report group from the sidebar. */
async function openGroup(page: Page, group: "Delivery" | "Estimation"): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.getByRole("link", { name: group, exact: true }).click();
}

/**
 * Narrow the open report to the golden sprint through the filter bar: the team, then the
 * period "One sprint", then that sprint by name. (The default period, the last 90 days, holds
 * none of the stub's sprints — they ended in 2025.)
 */
async function pickGoldenSprint(page: Page): Promise<void> {
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: "One sprint", exact: true }).click();
  await page.getByRole("combobox", { name: "Sprint", exact: true }).click();
  await page.getByRole("option", { name: GOLDEN.name, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
}

/** Narrow the open report through the filter bar: the seeded team, then a period preset (the bar is unfolded first). */
async function pickTeamAndPeriod(page: Page, period: string): Promise<void> {
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await team.click();
  await team.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("combobox", { name: "Period", exact: true }).click();
  await page.getByRole("option", { name: period, exact: true }).click();
}

/**
 * A distribution's percentile strip OR its counts-only minimum-sample notice — the notice matched
 * by its own wording, so the cycle-time trend's "no period has enough items" note cannot stand in
 * for it. The union is what `.first()` wraps at the call sites.
 */
const percentilesOrNotice = (page: Page): Locator =>
  page
    .getByText("Median (p50)")
    .or(page.getByRole("note").filter({ hasText: /are needed to show|Nothing to measure/ }));

/** The golden sprint's table row (its name is the only such row on a page). */
const goldenRow = (page: Page): Locator => page.getByRole("row", { name: new RegExp(GOLDEN.name) });

/**
 * Sign in as the regular user and open a report by its deep link, narrowed to THIS spec's
 * connection (an unnarrowed unit-level report would also count the other specs' synced
 * connections) and to the stub's fixed window. `extra` adds the team or scope under test.
 */
async function openReport(page: Page, path: string, extra = "", window = WINDOW): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.goto(`${path}?connectionId=${dataSourceId}&${window}${extra}`);
}

/** Choose `option` in the filter bar's searchable dropdown called `label` (typing narrows the list, the click picks). */
async function pickFilter(page: Page, label: string, option: string): Promise<void> {
  const select = page.getByRole("combobox", { name: label, exact: true });
  await select.click();
  await select.fill(option);
  await page.getByRole("option", { name: option, exact: true }).click();
}

/** Empty a filter dropdown through its clear button (Mantine hides that button from the accessibility tree, so it is found by its label). */
async function clearFilter(page: Page, label: string): Promise<void> {
  await page.getByLabel(`Clear ${label}`, { exact: true }).click();
}

/**
 * Every distribution's accounting ends in a dimmed "n + reason + … = population" line; each must
 * add up (the server's per-view partition), and `lines` of them are on the page.
 */
async function expectAccountingToReconcile(page: Page, lines: number): Promise<void> {
  const equations = page.getByText(/^\d+( \+ \d+)+ = \d+$/);
  await expect(equations).toHaveCount(lines);
  for (const text of await equations.allInnerTexts()) {
    const [terms, total] = text.split(" = ");
    expect(terms.split(" + ").map(Number).reduce((sum, term) => sum + term, 0), text).toBe(Number(total));
  }
}

/** The population the first accounting list states: "Of N finished in this period:". */
async function finishedInPeriod(page: Page): Promise<number> {
  const text = await page.getByText(/^Of \d+ finished in this period:$/).first().innerText();
  return Number(/\d+/.exec(text)?.[0]);
}

/** One figure of an adjustments block (Started, Changed after start, …): the value under its label. */
async function blockFigure(block: Locator, label: string): Promise<string> {
  return (await block.getByText(label, { exact: true }).locator("xpath=following-sibling::*[1]").innerText()).trim();
}

// The populated counterpart of the accessibility sweep, which scans these pages in whatever
// state the stack happens to be in: here each is scanned with real derived data behind it (the
// charts, the heat table, the epic plan), once its content settled — one line per page, and a
// clean page is an empty list.
async function populatedViolations(page: Page, targets: { path: string; extra?: string; settled: (p: Page) => Locator }[]) {
  const findings: Record<string, unknown[]> = {};
  for (const { path, extra = "", settled } of targets) {
    await page.goto(`${path}?connectionId=${dataSourceId}&${WINDOW}${extra}`);
    await expect(settled(page)).toBeVisible();
    findings[path] = await axeViolations(page);
  }
  return Object.fromEntries(Object.entries(findings).filter(([, violations]) => violations.length > 0));
}

/** The epic-progress journey: unit → the FLO domain → the golden epic, with its plan panel and daily table. */
async function drillIntoGoldenEpic(page: Page): Promise<void> {
  await openReport(page, "/reports/epic-progress");
  await expect(page.getByRole("heading", { level: 2, name: "Epic progress", exact: true })).toBeVisible();

  // The unit level: the headline tiles, and the domains table (the epic basis) beside the teams.
  await expect(page.getByRole("heading", { level: 3, name: "Whole unit", exact: true })).toBeVisible();
  for (const tile of ["PV (MD)", "EV (MD)", "AC (MD)", "SPI", "CPI"]) await expect(page.getByRole("group", { name: tile, exact: true })).toBeVisible();
  const domains = page.getByRole("table", { name: "Domains", exact: true });
  await expect(domains.getByRole("link", { name: "Show FLO", exact: true })).toBeVisible();
  await expect(page.getByRole("table", { name: "Teams (sprint view)", exact: true }).getByRole("link", { name: `Show ${teamName}`, exact: true })).toBeVisible();

  // Into the FLO domain: its epics, the golden one among them; the period and connection travel along.
  await domains.getByRole("link", { name: "Show FLO", exact: true }).click();
  await expect(page).toHaveURL(/domain=FLO/);
  await expect(page.getByRole("heading", { level: 3, name: "Domain: FLO", exact: true })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`connectionId=${dataSourceId}`));
  await page.getByRole("table", { name: "Epics", exact: true }).getByRole("link", { name: `Show ${GOLDEN_EPIC.issueKey}`, exact: true }).click();

  // The epic: the plan panel carries the generator's budget and planned dates; the breadcrumb leads back.
  await expect(page).toHaveURL(new RegExp(`epicId=${GOLDEN_EPIC.issueKey}`));
  await expect(page.getByRole("heading", { level: 3, name: new RegExp(`^Epic: ${GOLDEN_EPIC.issueKey}`) })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Where you are in the drill-down" }).getByRole("link", { name: "FLO", exact: true })).toBeVisible();
  // The plan was fully spent long before the window ends, so PV IS the budget.
  await expect(page.getByRole("group", { name: "PV (MD)", exact: true })).toContainText(md(GOLDEN_EPIC.budgetMd));
  const plan = page.getByRole("heading", { level: 3, name: "Plan", exact: true }).locator("xpath=ancestor::*[contains(@class,'Paper')][1]");
  await expect(plan).toContainText(`${md(GOLDEN_EPIC.budgetMd)} MD`);
  await expect(plan).toContainText(GOLDEN_EPIC.startDate);
  await expect(plan).toContainText(GOLDEN_EPIC.dueDate);

  // The chart's text alternative lists the days, newest first.
  await expect(page.getByRole("group", { name: "Chart: cumulative planned value, earned value and actual cost in man-days by day" })).toBeVisible();
  await page.getByRole("button", { name: "Show daily figures" }).click();
  const daily = page.getByRole("table", { name: "Cumulative PV, EV and AC by day, as a table" });
  await expect(daily.getByRole("row").nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  expect(await daily.getByRole("row").count(), "header + one row per day of the window").toBeGreaterThan(100);
}

/** The data-quality journey: overview tiles against their cards, the stub-guaranteed findings, no admin link. */
async function readDataQualityFindings(page: Page): Promise<void> {
  await openReport(page, "/reports/data-quality");
  await expect(page.getByRole("heading", { level: 2, name: "Data quality", exact: true })).toBeVisible();

  // The overview: what was checked, and the headline tiles.
  const populations = page.getByRole("group", { name: "What was checked" });
  await expect(populations.getByText("Done tasks", { exact: true })).toBeVisible();
  const tiles = page.getByRole("group", { name: "Headline counts, each linking to its card" });
  const tileValue = async (label: string): Promise<number> => {
    const tile = tiles.getByRole("link", { name: label, exact: true }).locator("xpath=..");
    return Number((await tile.locator("p").first().innerText()).trim());
  };

  // The stub guarantees these: work logged days late, authors on no team, and the OPS Kanban work no sprint holds.
  const cards = page.getByRole("group", { name: "Findings" });
  const findings = [
    { tile: "Over 1 day late", card: "Late logging" },
    { tile: "Authors without a team", card: "Authors without a team" },
    { tile: "Work done outside any sprint", card: "Work done outside any sprint" },
  ];
  for (const { tile, card } of findings) {
    const count = await tileValue(tile);
    expect(count, `${tile} is found in the stub data`).toBeGreaterThan(0);
    // The card states the same count the tile does.
    const cardBox = cards.getByRole("group", { name: card, exact: true });
    await expect(cardBox.getByText(`Found: ${count}`, { exact: true })).toBeVisible();
  }
  await expect(cards.getByRole("group", { name: "Work done outside any sprint", exact: true }).getByText(/\bOPS-\d+/).first()).toBeVisible();

  // A tile leads to its card without touching the URL; focus lands in it.
  const before = page.url();
  await tiles.getByRole("link", { name: "Authors without a team", exact: true }).click();
  await expect(cards.getByRole("group", { name: "Authors without a team", exact: true })).toBeFocused();
  expect(page.url()).toBe(before);

  // The configuration findings name their connection, but only an administrator gets the link to fix it.
  await expect(cards.getByRole("group", { name: "Boards without a team", exact: true })).toContainText("Found:");
  await expect(page.getByRole("link", { name: /^Open the metrics configuration of / })).toHaveCount(0);
}

/** The cost-matrix journey: the heat table, its totals, and the drill to the roster team's authors. */
async function readCostMatrix(page: Page): Promise<void> {
  await openReport(page, "/reports/cost-matrix");
  await expect(page.getByRole("heading", { level: 2, name: "Cost matrix", exact: true })).toBeVisible();

  // The heat table: author teams in rows, the stub's four domains in columns, totals both ways.
  const matrix = page.getByRole("table", { name: /^Man-days logged in the period: author teams in rows/ });
  for (const column of ["FLO", "GTM", "OPS", "PLT", "Total", "Foreign work (MD)", "Foreign share"]) {
    await expect(matrix.getByRole("columnheader", { name: column, exact: true })).toBeVisible();
  }
  const teamRow = matrix.getByRole("row", { name: new RegExp(`^Show ${teamName} `) });
  await expect(teamRow).toBeVisible();
  const totalsRow = matrix.getByRole("row", { name: /^Total / });
  const cellValues = async (row: Locator): Promise<number[]> =>
    (await row.getByRole("cell").allInnerTexts()).map((text) => Number(text.replace("%", "")));
  const rowTotal = async (row: Locator) => (await cellValues(row))[4];
  // The totals row repeats the tile; the rows add up to it (each figure is rounded once, so within 0.005 apiece).
  const grandTotal = await rowTotal(totalsRow);
  await expect(page.getByRole("group", { name: "Man-days logged", exact: true })).toContainText(String(grandTotal));
  const rowsSum = (await matrix.getByRole("row").filter({ hasNot: page.getByRole("columnheader") }).evaluateAll((rows) =>
    rows.map((row) => row.querySelectorAll("td")[4]?.textContent ?? "").map(Number),
  ));
  const bodyTotals = rowsSum.slice(0, -1);
  expect(bodyTotals.reduce((sum, value) => sum + value, 0)).toBeCloseTo(grandTotal, 1);

  // Into the team: its authors are the rows now (the roster person among them), the period carried along.
  await teamRow.getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "Man-days by author and domain", exact: true })).toBeVisible();
  const authors = page.getByRole("table", { name: /^Man-days logged in the period: the team's authors in rows/ });
  await expect(authors.getByRole("columnheader", { name: "Author", exact: true })).toBeVisible();
  await expect(authors.getByRole("row", { name: /^Show Sample User \d+ / }).first()).toBeVisible();
}

/**
 * The epic-accuracy journey: empty default window, both views (each a median/mean/items strip with no p90/p95 and an "Accuracy
 * measures" table, or the counts-only notice), the golden epic's ratio, the domain, then the required team (no "All teams") and no
 * Member control. Every deep link carries the spec's OWN team: the page is read per team, and a bare link would start on the first
 * team by name, which other specs' teams can precede.
 */
async function readEpicAccuracy(page: Page): Promise<void> {
  const teamLink = `&teamId=${teamId}`;
  // The default window holds no finished epic: the empty state, not a block of zeros.
  await openReport(page, "/reports/epic-estimation-accuracy", teamLink);
  await expect(page.getByRole("heading", { level: 2, name: "Epic estimation accuracy", exact: true })).toBeVisible();
  await expect(page.getByText("No data in this period")).toBeVisible();
  const epics = page.getByRole("table", { name: "Finished epics", exact: true });
  await expect(epics).toHaveCount(0);

  // The year-long window holds the stub's finished epics: both views, each a distribution with its accounting.
  await page.goto(`/reports/epic-estimation-accuracy?connectionId=${dataSourceId}&${EPIC_WINDOW}${teamLink}`);
  await expect(page.getByRole("heading", { name: "Against the own estimate at start", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Against the own estimate at done", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
  // The team's few epics may sit under the minimum sample: a view is then the counts-only notice, with neither strip nor measures.
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  await expectCentreStripAndMeasures(page);

  // One row per finished epic, as many as the accounting's population.
  const golden = epics.getByRole("row", { name: new RegExp(`^${GOLDEN_EPIC.issueKey}\\b`) });
  await expect(golden).toBeVisible();
  expect((await epics.getByRole("row").count()) - 1, "a row per finished epic").toBe(await finishedInPeriod(page));

  // The golden epic: the ratio is actual ÷ its OWN estimate — never the sum of its children's.
  const cells = golden.getByRole("cell");
  await expect(cells.nth(1)).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(cells.nth(2)).toHaveText(md(GOLDEN_EPIC.budgetMd));
  await expect(cells.nth(3)).toHaveText(md(GOLDEN_EPIC.budgetMd));
  await expect(cells.nth(4)).toHaveText(md(GOLDEN_EPIC.childSumMd));
  const actual = Number(await cells.nth(5).innerText());
  const ratio = Number(await cells.nth(6).innerText());
  expect(actual).toBeGreaterThan(0);
  expect(Math.abs(ratio - actual / GOLDEN_EPIC.budgetMd), "ratio = actual ÷ own estimate").toBeLessThan(0.006);
  expect(Math.abs(ratio - actual / GOLDEN_EPIC.childSumMd), "not actual ÷ child sum").toBeGreaterThan(0.05);
  // The read is the picked team's: no other team's epics.
  await expect(epics.getByRole("row", { name: /^(GTM|OPS|PLT)-\d+/ })).toHaveCount(0);

  // The domain narrows the epics: the seeded team owns no GTM epic, so GTM leaves nothing; clearing it brings the golden FLO epic back.
  await openFilters(page);
  await pickFilter(page, "Domain", "GTM");
  await expect(page).toHaveURL(/domain=GTM/);
  await expect(page.getByText("No data in this period")).toBeVisible();
  await expect(epics).toHaveCount(0);
  await clearFilter(page, "Domain");
  await expect(golden).toBeVisible();

  // A team is required and there is no Member control: epics belong to a team, not a person.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("combobox", { name: "Member", exact: true })).toHaveCount(0);
}

/**
 * The estimate-adjustments journey: the tasks and epics blocks of the spec's own team (the page is always read for one team, so a
 * bare link would start on the first team by name, which other specs' teams can precede), the activity-type filter, the required
 * team (no "All teams") and a one-person pick.
 */
async function readEstimateAdjustments(page: Page): Promise<void> {
  await openReport(page, "/reports/estimate-adjustments", `&teamId=${teamId}`, EPIC_WINDOW);
  await expect(page.getByRole("heading", { level: 2, name: "Estimate adjustments", exact: true })).toBeVisible();
  await expect(page.getByText("The domain view applies to tasks; epics always read their own space.")).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // Tasks and epics each read started / changed after start / estimated late / share changed. The seeded team is small, so the share
  // may be withheld behind the minimum-sample notice: only the relations between the counts are guaranteed.
  const tasks = page.getByRole("group", { name: "Tasks", exact: true });
  const epics = page.getByRole("group", { name: "Epics", exact: true });
  for (const block of [tasks, epics]) {
    const started = Number(await blockFigure(block, "Started"));
    const changed = Number(await blockFigure(block, "Changed after start"));
    const late = Number(await blockFigure(block, "Estimated late"));
    expect(changed).toBeLessThanOrEqual(started);
    expect(late, "estimated late is counted within changed after start").toBeLessThanOrEqual(changed);
  }
  // Each kind's change distribution (start → done) is a percentile strip or the counts-only notice, with its accounting.
  await expect(page.getByRole("heading", { name: "Change from start to done", exact: true })).toHaveCount(2);
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();

  // The activity type narrows the TASKS (epics carry none): the filter lands in the URL, and clearing it restores the figure.
  await openFilters(page);
  const allTasks = Number(await blockFigure(tasks, "Started"));
  // The slice round-trip is proven, not vacuous: the request carries the filter, and the narrowed figure is at most the unfiltered one.
  const bugResponse = page.waitForResponse((r) => {
    const url = new URL(r.url());
    return r.request().method() === "GET" && url.pathname === "/api/v1/reports/estimate-adjustments" && url.searchParams.get("activityType") === "Bug" && r.ok();
  });
  await pickFilter(page, "Activity type", "Bug");
  await bugResponse;
  await expect(page).toHaveURL(/activityType=Bug/);
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBeLessThanOrEqual(allTasks);
  await clearFilter(page, "Activity type");
  await expect(page).not.toHaveURL(/activityType=/);
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBe(allTasks);

  // A team is required: no "All teams", no clear button.
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and keeps the team; "Totals per person" replaces the by-member table with one row.
  // (The stub person may have too few changes for a histogram, so the "1." numbering is optional here; the stacking itself is the
  // page's unit tests'.) A single person has no epic figures.
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  await expect(page.getByRole("note").filter({ hasText: "Epics aren't attributed to individual people" })).toBeVisible();
  await expect(epics).toHaveCount(0);
  await expect(tasks).toBeVisible();
}

/** Sprint consistency, on the golden sprint: one team and no Member control, then sliced by Activity type (live figures, no drift) and back. */
async function sliceSprintConsistency(page: Page): Promise<void> {
  const cells = goldenRow(page).getByRole("cell");
  // The page always reads ONE team (the tab switch carried it) and every member of it: no Member control.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await openFilters(page);
  await expect(page.getByRole("combobox", { name: "Team", exact: true })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Member", exact: true })).toHaveCount(0);

  // Sliced by activity type the figures are live and smaller, with no frozen snapshot (so no drift badge) and the page says so.
  await pickFilter(page, "Activity type", "Bug");
  await expect(page).toHaveURL(/activityType=Bug/);
  await expect(page.getByText(/^Sliced by activity type or work category/)).toBeVisible();
  await expect(cells.nth(3)).not.toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
  await expect(page.getByText("Drift", { exact: true })).toHaveCount(0);
  // Cleared, the golden figures are back.
  await clearFilter(page, "Activity type");
  await expect(page).not.toHaveURL(/activityType/);
  await expect(page.getByText(/^Sliced by activity type or work category/)).toHaveCount(0);
  await expect(cells.nth(3)).toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
}

/**
 * The cycle-time journey: the golden sprint's two views and trend, the epics beside them at team level, a required team (no
 * "All teams") and a one-person pick.
 */
async function readCycleTime(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await pickGoldenSprint(page);
  await page.getByRole("tab", { name: "Cycle time", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

  // Both views state their own accounting, and each is either a distribution or the counts-only notice.
  await expect(page.getByRole("heading", { name: "Working days", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Elapsed days", exact: true })).toBeVisible();
  await expect(page.getByText(/^Of \d+ finished in this period:$/).first()).toBeVisible();
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  // The golden sprint's team finished work in the window, so the per-period trend has its table.
  await expect(page.getByRole("table", { name: "Trend, as a table" })).toBeVisible();
  // Cycle time is read for one team: the picked team stays in the URL.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // The epics beside the tasks: the stub's epics all finished before the sprint-narrowed window, so a year-long
  // window reads them — two views of their own, each with its own accounting, next to the tasks' two.
  await page.goto(`/reports/cycle-time?connectionId=${dataSourceId}&${EPIC_WINDOW}&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 3, name: "Epics", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Epics: elapsed days", exact: true })).toBeVisible();
  await expectAccountingToReconcile(page, 4);
  // At team level the epics card stays (the team's own epics) and there is no owner table: epics split by owner team, never by person.
  await expect(page.getByRole("table", { name: "Epics by owner team", exact: true })).toHaveCount(0);

  // A team is required: no "All teams", no clear button.
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and "Totals per person" replaces the by-member table with one row. (The stub person may
  // have too few finished tasks for a histogram, so the "1." numbering is optional here; the stacking itself is the page's unit tests'.)
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  // The epics are not per person: one note replaces the epics' histograms.
  await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toHaveCount(0);
}

/**
 * The task-accuracy journey: the golden sprint's two views, each either a distribution (a median/mean/items strip with no p90 or
 * p95, and an "Accuracy measures" table) or the counts-only notice; a required team (no "All teams") and a one-person pick.
 */
async function readTaskAccuracy(page: Page): Promise<void> {
  await openGroup(page, "Estimation");
  await expect(page.getByRole("heading", { level: 2, name: "Task estimation accuracy", exact: true })).toBeVisible();
  await pickGoldenSprint(page);

  // Two views — at start (primary) and at done — each with its own "left out" accounting.
  await expect(page.getByRole("heading", { name: "Against the estimate at start" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Against the estimate at done" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expect(page.getByText(/^Of \d+ finished in this period:$/).first()).toBeVisible();
  // A distribution (percentile strip) or, below the minimum sample, the counts-only notice.
  await expect(percentilesOrNotice(page).first()).toBeVisible();
  await expectCentreStripAndMeasures(page);
  // Task accuracy is read for one team: the picked team stays in the URL.
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

  // A team is required: no "All teams", no clear button.
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");

  // One person picked: the URL gains the account and "Totals per person" lists them in one row. (The stub person may have too few
  // finished tasks for a histogram, so the "1." numbering is not asserted here; the two-person stacking is the page's unit tests'.)
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toContainText(memberName);
  await expectCentreStripAndMeasures(page);
}

/**
 * Every shown view has the median/mean/items strip — never a p90 or p95 — and its own nine-row "Accuracy measures" table; a view
 * below the minimum sample has neither and the counts-only notice ("Only N items in this selection") stands instead. The page has
 * two views (at start, at done), so strips + notices must account for both — unless the selection holds nothing at all, when the
 * page shows its one "No data in this period" state instead of either view. The check never passes on a page that shows none of these.
 */
async function expectCentreStripAndMeasures(page: Page): Promise<void> {
  const strips = page.getByRole("group", { name: "Percentiles", exact: true });
  const measures = page.getByRole("table", { name: /^Accuracy measures — / });
  const notices = page.getByRole("note").filter({ hasText: /^(Only \d+ items? in this selection|Nothing to measure in this selection)/ });
  const empty = page.getByText("No data in this period", { exact: true });
  await expect
    .poll(async () => (await empty.count()) > 0 || (await strips.count()) + (await notices.count()) === 2)
    .toBe(true);
  // Every shown view has exactly one measures table beside its strip. A polled difference (never a one-off count snapshot, which a
  // stale previous report could supply) settles on 0 once the page shows one coherent report: both counts are 0 (the notice) or equal.
  await expect.poll(async () => (await measures.count()) - (await strips.count())).toBe(0);
  await expect(page.getByText("p90", { exact: true })).toHaveCount(0);
  await expect(page.getByText("p95", { exact: true })).toHaveCount(0);
  for (let i = 0; i < (await strips.count()); i++) {
    await expect(strips.nth(i).getByText("Median (p50)", { exact: true })).toBeVisible();
    await expect(strips.nth(i).getByText("Mean", { exact: true })).toBeVisible();
    await expect(strips.nth(i).getByText("Items", { exact: true })).toBeVisible();
    await expect(measures.nth(i).getByRole("row")).toHaveCount(10);
  }
}

/** The cycle-time long-tail journey: the default 5% open bucket, widened to 25%, switched off with 0, and back to the default. */
async function widenCycleTimeTail(page: Page): Promise<void> {
  // Cycle time is read per team, so the link carries this spec's OWN team (a bare link would start on the first team by name,
  // which other specs' teams can precede).
  await openReport(page, "/reports/cycle-time", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { name: "Elapsed days", exact: true })).toBeVisible();
  // Elapsed days are continuous (milliseconds), so the top values are never tied: the tail bucket is certain to exist at any share above 0.
  const lastRange = page
    .getByRole("table", { name: "Histogram, as a table — Elapsed days", exact: true })
    .getByRole("row")
    .last()
    .getByRole("cell")
    .first();

  // The default 5%: not in the URL, yet the last bucket is already the open "≥ X" tail, marked in words.
  const tail = page.getByRole("combobox", { name: "Long tail", exact: true });
  await expect(tail).toHaveValue("5%");
  expect(new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(/^≥ \S+ \(long tail\)$/);
  const narrow = await lastRange.innerText();

  // Widening to 25% lowers the tail's start and puts the choice in the URL.
  await tail.click();
  await page.getByRole("option", { name: "25%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=25/);
  await expect(tail).toHaveValue("25%");
  await expect(lastRange).toHaveText(/^≥ \S+ \(long tail\)$/);
  await expect(lastRange).not.toHaveText(narrow);

  // 0 restores the plain histogram: no open bucket at all.
  await tail.click();
  await page.getByRole("option", { name: "0%", exact: true }).click();
  await expect(page).toHaveURL(/tailPercent=0/);
  await expect(lastRange).not.toHaveText(/^≥/);

  // Back to the default 5%: the param leaves the URL.
  await tail.click();
  await page.getByRole("option", { name: "5%", exact: true }).click();
  await expect.poll(() => new URL(page.url()).searchParams.has("tailPercent")).toBe(false);
  await expect(lastRange).toHaveText(/^≥ /);
}

/**
 * The reported-time journey: the Estimation tab hop, both distributions, the domain and domain-view controls, a required team
 * (no "All teams") and a one-person pick (the spec's team has ONE stub person, so the two-person stacking is vitest's).
 */
async function readReportedTime(page: Page): Promise<void> {
  // Reached through the Estimation tabs: the route is `reported-time-ratio`, the tab "Reported time". The link carries the spec's OWN
  // team: reported time is read per team and a bare link would start on the first team by name, which other specs' teams can precede.
  await openReport(page, "/reports/epic-estimation-accuracy", `&teamId=${teamId}`);
  await page.getByRole("tab", { name: "Reported time", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/reports/reported-time-ratio\\?.*connectionId=${dataSourceId}`));
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 2, name: "Reported time", exact: true })).toBeVisible();

  // Two measures, two distributions, each with its OWN accounting (the partitions differ).
  await expect(page.getByRole("heading", { name: "Reported time ÷ cycle time", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Very short cycles give very large ratios/)).toBeVisible();
  await expect(page.getByRole("table", { name: /^Histogram, as a table — Reported time ÷ cycle time$/ })).toBeVisible();

  // The domain narrows what finished (the team's work is mostly FLO, so it may not shrink): the filter is in the URL.
  const all = await finishedInPeriod(page);
  await openFilters(page);
  await pickFilter(page, "Domain", "FLO");
  await expect(page).toHaveURL(/domain=FLO/);
  await expect.poll(() => finishedInPeriod(page)).toBeLessThanOrEqual(all);
  await clearFilter(page, "Domain");
  await expect.poll(() => finishedInPeriod(page)).toBe(all);

  // The domain view (delivered in / earned in) is a report-specific control that travels in the URL.
  await page.getByRole("radiogroup", { name: "Domain view" }).getByText("Earned in", { exact: true }).click();
  await expect(page).toHaveURL(/domainView=EPIC/);
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();

  // A team is required: the Team select shows it, cannot be cleared and offers no "All teams"; the team's members are the drill table.
  await openFilters(page);
  const team = page.getByRole("combobox", { name: "Team", exact: true });
  await expect(team).toHaveValue(teamName);
  await expect(page.getByLabel("Clear Team", { exact: true })).toHaveCount(0);
  await team.click();
  await expect(page.getByRole("option", { name: teamName, exact: true })).toBeVisible();
  await expect(page.getByRole("option", { name: "All teams", exact: true })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();

  // One person picked: the URL gains the account, Totals per person has one row, and the flow-efficiency histogram carries a
  // "1. <name>" column. A small stub sample (below minSampleSize) hides the distribution — then the min-sample note stands in
  // for the histogram and the Totals row shows the bare name — so those two assertions accept both outcomes.
  await pickFilter(page, "Member", memberName);
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/accountId=/);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
  const totals = page.getByRole("table", { name: "Totals per person", exact: true });
  await expect(totals.getByRole("row")).toHaveCount(2);
  await expect(totals.getByRole("row").nth(1).getByRole("cell").first()).toHaveText(new RegExp(`^(1\\. )?${memberName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`));
  const personColumn = page
    .getByRole("table", { name: "Histogram, as a table — Flow efficiency", exact: true })
    .getByRole("columnheader", { name: `1. ${memberName}`, exact: true });
  const minSampleNote = page.getByRole("note").filter({ hasText: /^Only \d+ items? in this selection/ });
  await expect(personColumn.or(minSampleNote).first()).toBeVisible();
  expect(await finishedInPeriod(page), "one person's finished work is part of the team's").toBeLessThan(all);
}

/** The estimated-backlog journey: a team's tiles and trend, the unit's pace note, a domain's empty pace, team replacing domain. */
async function readEstimatedBacklog(page: Page): Promise<void> {
  // The team's backlog: its board is mapped, so the pace of its closed sprints turns man-days into sprints.
  await openReport(page, "/reports/backlog", `&teamId=${teamId}`);
  await expect(page.getByRole("heading", { level: 2, name: "Estimated backlog", exact: true })).toBeVisible();
  // The card is "as of" the newest derived day inside the window — never later than the window's end.
  const asOf = /^Backlog on (\d{4}-\d{2}-\d{2})$/.exec(await page.getByRole("heading", { level: 3, name: /^Backlog on / }).innerText())?.[1] ?? "";
  expect(asOf, "the as-of day").toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(asOf <= dayOffset(7), "as-of inside the window").toBe(true);
  const tile = (label: string) => page.getByRole("group", { name: label, exact: true });
  const teamMd = (await tile("Backlog (MD)").locator("p").nth(1).innerText()).trim();
  const teamItems = (await tile("Items").locator("p").nth(1).innerText()).trim();
  expect(Number(teamMd)).toBeGreaterThan(0);
  expect(teamItems).toMatch(/^[1-9]\d*$/);
  await expect(tile("Backlog in sprints")).toContainText(/≈ [\d.]+ sprints? ahead/);
  await expect(tile("Backlog in sprints")).toContainText(/Recent pace: [\d.]+ MD delivered per sprint, the mean of \d+ closed sprints?/);

  // The trend: the chart, and its text alternative — the newest day first, equal to the tiles' as-of day and figures.
  await expect(page.getByRole("group", { name: "Chart: estimated backlog in man-days by day" })).toBeVisible();
  await page.getByRole("button", { name: "Show daily figures" }).click();
  const daily = page.getByRole("table", { name: "Backlog by day, as a table" });
  const newest = daily.getByRole("row").nth(1).getByRole("cell");
  await expect(newest.nth(0)).toHaveText(asOf);
  await expect(newest.nth(1)).toHaveText(teamMd);
  await expect(newest.nth(2)).toHaveText(teamItems);
  expect(await daily.getByRole("row").count(), "header + one row per day of the window").toBeGreaterThan(100);

  // The unit: the whole backlog (the unowned part too), its pace the SUM of the teams' — and it says so.
  await openFilters(page);
  await clearFilter(page, "Team");
  await expect(page).not.toHaveURL(/teamId=/);
  await expect(tile("Backlog in sprints")).toContainText("At unit level the pace is the sum of each team's own mean");
  await expect.poll(async () => Number((await tile("Backlog (MD)").locator("p").nth(1).innerText()).trim())).toBeGreaterThan(Number(teamMd));

  // A domain has no velocity of its own: the sprints tile says why it is empty instead of inventing a pace.
  await pickFilter(page, "Domain", "FLO");
  await expect(page).toHaveURL(/domain=FLO/);
  await expect(tile("Backlog in sprints")).toContainText("this selection has no velocity of its own");
  await expect(tile("Backlog in sprints").locator("p").nth(1)).toHaveText("—");

  // There is no team × domain split: picking a team replaces the domain.
  await pickFilter(page, "Team", teamName);
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page).not.toHaveURL(/domain=/);
}

/** Pick `option` in the Deep dive selection panel's searchable dropdown called `label` (a multiple choice stays open, so the list is closed after). */
async function pickInPanel(page: Page, label: string, option: string | RegExp): Promise<void> {
  const select = page.getByRole("combobox", { name: label, exact: true });
  await select.click();
  await page.getByRole("option", { name: option, exact: typeof option === "string" }).click();
  await page.keyboard.press("Escape");
}

/** One computed style property of the first element `locator` finds — what the stylesheet really resolved to in this browser. */
async function computed(locator: Locator, property: string): Promise<string> {
  return locator.first().evaluate((element, name) => getComputedStyle(element).getPropertyValue(name), property);
}

/** A computed length in px, so "non-zero" is a number comparison rather than a string guess. */
async function computedPx(locator: Locator, property: string): Promise<number> {
  return Number.parseFloat(await computed(locator, property));
}

/** The Deep dive journey: pick the golden sprint's domain and sprint, drill an epic, a month and a cell, switch a layer, read the Burn-up tab, then an epic's window. */
async function diveIntoGoldenSprint(page: Page): Promise<void> {
  await login(page, reader.email, reader.password);
  await page.getByRole("link", { name: "Deep dive", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Deep dive", exact: true })).toBeVisible();
  // Nothing is requested before a selection: the explainer of the three modes stands in.
  await expect(page.getByRole("heading", { level: 3, name: "Pick something to dive into", exact: true })).toBeVisible();

  // Sprints of a domain: this spec's connection (the picker shows only when the stack holds more than one active connection),
  // the golden sprint's domain, the golden sprint.
  await expect(page.getByRole("combobox", { name: "Domain", exact: true })).toBeVisible();
  if (await page.getByRole("combobox", { name: "Connection", exact: true }).count()) await pickFilter(page, "Connection", dataSourceName);
  await pickFilter(page, "Domain", "FLO");
  await pickInPanel(page, "Sprints", GOLDEN.name);
  await page.getByRole("button", { name: "Show", exact: true }).click();
  // The URL is the selection, in its canonical order.
  await expect(page).toHaveURL(new RegExp(`/reports/deep-dive\\?domain=FLO&sprintId=${GOLDEN.sprintId}(&connectionId=${dataSourceId})?$`));

  // The matrix: all three layers drawn, the legend naming each, a done marker, and the golden epic's neighbours as rows.
  const grid = page.getByRole("grid", { name: "Plan, execution and cost by epic and time" });
  await expect(grid).toBeVisible();
  const layers = page.getByRole("group", { name: "Layers shown", exact: true });
  for (const layer of ["Plan (PV)", "Execution", "Cost (AC)"]) await expect(layers.getByRole("switch", { name: layer, exact: true })).toBeChecked();
  for (const layer of ["pv", "exec", "cost", "done"]) await expect(page.locator(`[data-layer="${layer}"]`).first()).toBeVisible();
  await expect(page.getByRole("group", { name: "Legend", exact: true })).toBeVisible();

  // An epic opens into its tasks: the toggle says so, and the tasks are rows under it.
  const epic = grid.getByRole("button", { name: /^FLO-36 /, expanded: false });
  await epic.click();
  await expect(grid.getByRole("button", { name: /^FLO-36 /, expanded: true })).toBeVisible();
  await expect(grid.getByRole("rowheader", { name: /^FLO-52\b/ })).toBeVisible();

  // A month opens into weeks; focus lands on the first week's header.
  await grid.getByRole("button", { name: /^Expand \d{4}-\d{2} into weeks$/ }).click();
  const weeks = grid.getByRole("button", { name: /^Expand \d{4}-W\d{2} into days$/ });
  await expect(weeks.first()).toBeFocused();
  await expect(grid.getByRole("button", { name: /^Collapse \d{4}-\d{2}$/ })).toHaveAttribute("aria-expanded", "true");

  // A cell holding logged time carries its author: the keyboard focus alone opens the tooltip with the figures.
  const costCell = grid.getByRole("gridcell", { name: /^FLO-36 .*Cost \(AC\): [\d.]+ MD \(.*Sample User \d+: [\d.]+ MD/ }).first();
  await costCell.focus();
  const tooltip = page.getByRole("tooltip");
  await expect(tooltip).toBeVisible();
  await expect(tooltip).toContainText(/Plan \(PV\): [\d.]+ MD/);
  await expect(tooltip).toContainText(/Cost \(AC\): [\d.]+ MD/);
  await expect(tooltip.getByRole("listitem").filter({ hasText: /^Sample User \d+: [\d.]+ MD$/ }).first()).toBeVisible();

  // What the stylesheet resolved to in a real browser (the unit tests cannot compute styles).
  // A focused cell and the controls in the sticky chrome scroll clear of the sticky column and header rows.
  expect(await computedPx(costCell, "scroll-margin-left"), "a cell clears the sticky item column").toBeGreaterThan(0);
  expect(await computedPx(costCell, "scroll-margin-top"), "a cell clears the sticky header rows").toBeGreaterThan(0);
  expect(await computedPx(grid.getByRole("button", { name: /^FLO-36 /, expanded: true }), "scroll-margin-top"), "a row toggle clears the header rows").toBeGreaterThan(0);
  expect(await computedPx(weeks, "scroll-margin-left"), "a column toggle clears the item column").toBeGreaterThan(0);
  // A bar, the done marker and the window outline each keep a halo in the surface colour so their edges meet the surface.
  expect(await computed(page.locator('[data-layer="pv"]'), "box-shadow"), "a bar's halo").not.toBe("none");
  expect(await computed(page.getByText("◆"), "text-shadow"), "the done marker's halo").not.toBe("none");
  expect(await computed(page.locator("[data-window]"), "box-shadow"), "the window outline's ring").not.toBe("none");
  // The open card scrolls inside itself when taller than the screen.
  expect(await computed(tooltip, "max-height"), "the tooltip is height-capped").not.toBe("none");
  expect(await computed(tooltip, "overflow")).toBe("auto");

  // A layer toggled off takes its bars (and, for execution, the done markers) away; the others stay.
  await layers.getByRole("switch", { name: "Execution", exact: true }).click();
  await expect(page.locator('[data-layer="exec"]')).toHaveCount(0);
  await expect(page.locator('[data-layer="done"]')).toHaveCount(0);
  await expect(page.locator('[data-layer="pv"]').first()).toBeVisible();

  // The Burn-up tab shows the same selection day by day: the tab is in the URL, the chart names its series, the numbers sit
  // behind the disclosure (the newest day first), and a hop back to the matrix drops the param and redraws the grid.
  await page.getByRole("tab", { name: "Burn-up", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`[?&]view=burnup(&|$)`));
  await expect(page.getByRole("heading", { level: 3, name: "Burn-up", exact: true })).toBeVisible();
  const chart = page.getByRole("group", { name: "Chart: cumulative plan, earned value and cost in man-days by day" });
  await expect(chart).toBeVisible();
  for (const series of ["Plan (PV)", "Earned value (EV)", "Cost (AC)"]) await expect(chart.getByText(series, { exact: true })).toBeVisible();
  const toggle = page.getByRole("button", { name: "Show daily figures", exact: true });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await expect(page.getByRole("button", { name: "Hide daily figures", exact: true })).toHaveAttribute("aria-expanded", "true");
  const daily = page.getByRole("table", { name: "Cumulative figures per day", exact: true });
  await expect(daily).toBeVisible();
  const figure = /^-?\d+(\.\d+)?$/;
  // The newest day (the first row) always carries a plan figure; earned value and cost end where the data does, so the first row
  // that has them (an em dash otherwise) must hold numbers in all three columns.
  const rows = daily.getByRole("row");
  await expect(rows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(rows.nth(1).getByRole("cell").nth(1)).toHaveText(figure);
  const current = rows.filter({ has: page.getByRole("cell").nth(3), hasNot: page.getByRole("cell", { name: "—", exact: true }) }).first();
  for (const column of [1, 2, 3]) await expect(current.getByRole("cell").nth(column)).toHaveText(figure);
  await page.getByRole("tab", { name: "Matrix", exact: true }).click();
  await expect(page).not.toHaveURL(/view=/);
  await expect(grid).toBeVisible();
  await expect(page.getByRole("heading", { level: 3, name: "Burn-up", exact: true })).toHaveCount(0);

  // Epics mode, one epic: its planned window is outlined across the columns it spans.
  await page.getByRole("radiogroup", { name: "How to select work" }).getByText("Epics", { exact: true }).click();
  await pickInPanel(page, "Epics", new RegExp(`^${GOLDEN_EPIC.issueKey} `));
  await page.getByRole("button", { name: "Show", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/reports/deep-dive\\?epicId=${GOLDEN_EPIC.issueKey}(&connectionId=${dataSourceId})?$`));
  await expect(grid.getByRole("button", { name: new RegExp(`^${GOLDEN_EPIC.issueKey} `), expanded: false })).toBeVisible();
  await expect(page.locator("[data-window]").first()).toBeVisible();
}

/** The report's old address (it was called Velocity) still opens it, keeping the filters. */
async function expectLegacyVelocityRedirect(page: Page): Promise<void> {
  await page.goto("/reports/velocity?lastSprints=3");
  await expect(page).toHaveURL(/\/reports\/commitments\?.*lastSprints=3/);
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
}

/** The collapsible filter bar journey: folded by default, the badge counts a narrowing, the choice is remembered per report. */
async function foldAndUnfoldFilters(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  const toggle = page.getByRole("button", { name: /^Filters/ });
  const period = page.getByRole("combobox", { name: "Period", exact: true });

  // The bar starts folded: just the toggle, no badge, no controls — and the page already sits on a team (commitments never read the whole unit).
  await expect(page).toHaveURL(/teamId=\d+/);
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(toggle).toHaveAccessibleName("Filters");
  await expect(period).toHaveCount(0);

  // Unfolding shows the controls; a narrowing counts on the toggle, folded or not.
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(period).toBeVisible();
  // Commitments always has a team (it is required, so the bar offers no "all teams" to compare it with and the badge never counts it):
  // the narrowing that shows on the toggle here is the period.
  await period.click();
  await page.getByRole("option", { name: "Last 30 days", exact: true }).click();
  await expect(page).toHaveURL(/from=\d{4}-\d{2}-\d{2}&to=/);
  await expect(toggle).toHaveAccessibleName("Filters 1");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(period).toHaveCount(0);
  await expect(toggle).toHaveAccessibleName("Filters 1");

  // The choice is remembered per report: a reload keeps it folded, an unfolded bar stays unfolded.
  await page.reload();
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await toggle.click();
  await page.reload();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(period).toBeVisible();

  // Another report keeps its own flag: Throughput is still folded.
  await page.getByRole("tab", { name: "Throughput", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /^Filters/ })).toHaveAttribute("aria-expanded", "false");
}

/** Delivery, narrowed to the golden sprint, then the Throughput tab (which carries the period and team over). */
async function openGoldenThroughput(page: Page): Promise<void> {
  await openGroup(page, "Delivery");
  await pickGoldenSprint(page);
  await page.getByRole("tab", { name: "Throughput", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
}

/** The cumulative throughput journey: the Chart view switch, the URL param, the running-total columns, and the param dropped on the next tab. */
async function switchThroughputToCumulative(page: Page): Promise<void> {
  await openGoldenThroughput(page);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: delivered scope by sprint", exact: true })).toBeVisible();

  // Per period is the default and leaves the URL alone; Cumulative draws running totals and writes the choice.
  const view = page.getByRole("radiogroup", { name: "Chart view", exact: true });
  await expect(view.getByRole("radio", { name: "Per period", exact: true })).toBeChecked();
  await expect(page).not.toHaveURL(/view=/);
  await view.getByText("Cumulative", { exact: true }).click();
  await expect(page).toHaveURL(/view=cumulative/);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period, cumulative", exact: true })).toBeVisible();
  await expect(page.getByRole("group", { name: "Chart: delivered scope by sprint, cumulative", exact: true })).toBeVisible();

  // Every number of the areas is in a table: the last week's running total is the period's total.
  const summary = await page.getByText(/^Delivered in the period: [\d.]+ MD/).textContent();
  const periodMd = /: ([\d.]+) MD/.exec(summary ?? "")?.[1] ?? "";
  const bucketTable = page.getByRole("table", { name: "Delivered per period, as a table" });
  await expect(bucketTable.getByRole("columnheader", { name: "Cumulative (MD)", exact: true })).toBeVisible();
  await expect(bucketTable.getByRole("row").last().getByRole("cell").nth(3)).toHaveText(periodMd);
  // One sprint is in view, so its running total is its own delivered MD.
  await expect(goldenRow(page).getByRole("cell").nth(5)).toHaveText(md(GOLDEN.deliveredMd));

  // Back to per period drops the param; the choice follows no report tab (it is no filter).
  await view.getByText("Per period", { exact: true }).click();
  await expect(page).not.toHaveURL(/view=/);
  await expect(page.getByRole("group", { name: "Chart: delivered scope by period", exact: true })).toBeVisible();
  await view.getByText("Cumulative", { exact: true }).click();
  await page.getByRole("tab", { name: "Commitments", exact: true }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  await expect(page).not.toHaveURL(/view=/);
}

test.describe("reports, read by a regular user", () => {
  test.beforeAll(async () => {
    // A full stub sync (~1,200 issues) plus the derivation that follows it: the two waits below
    // are each bounded (340s / 240s / 240s), so the hook gets their sum plus headroom.
    test.setTimeout(960_000);
    ({ api } = await apiAsAdmin());
    const adminApi = api;
    ({ id: dataSourceId, name: dataSourceName } = await syncStubDataSourceViaApi(adminApi, "e2e-reports-ds"));
    teamName = uniqueText("e2e-reports-team");
    ({ teamId } = await configureMetricsViaApi(adminApi, dataSourceId, teamName));
    // A stub person on the team's roster, so the cost matrix has a real author-team row to drill into.
    ({ membershipId, displayName: memberName } = await addStubMemberViaApi(adminApi, teamId));

    const name = uniqueText("e2e-reports-reader");
    reader = { email: `${name.toLowerCase()}@flow.local`, password: "e2e-only-password" };
    const created = await adminApi.post("/api/v1/users", { data: { name, email: reader.email, password: reader.password, roles: [] } });
    expect(created.status(), await created.text()).toBe(201);
    userId = (await created.json()).id as number;

    await awaitDerivedSprint(adminApi, teamId, GOLDEN.sprintId);
    await awaitDerivedTeamCost(adminApi, dataSourceId, teamId, WINDOW);
  });

  test.afterAll(async () => {
    // A FRESH admin session: the access token beforeAll minted (900 s) may have expired across the
    // bounded waits. Children first: the connection (soft delete), the roster membership (a deleted
    // team keeps its rows, and a person belongs to one team at a time, globally — so it goes BEFORE
    // the team), the team its board pointed at, the reader.
    const { api: cleanup } = await apiAsAdmin();
    const paths = [
      dataSourceId === undefined ? null : `/api/v1/data-sources/${dataSourceId}`,
      membershipId === undefined || teamId === 0 ? null : `/api/v1/teams/${teamId}/jira-memberships/${membershipId}`,
      teamId === 0 ? null : `/api/v1/teams/${teamId}`,
      userId === undefined ? null : `/api/v1/users/${userId}`,
    ];
    for (const path of paths) {
      if (path === null) continue;
      const removed = await cleanup.delete(path);
      expect([204, 404], `${path} -> ${removed.status()}`).toContain(removed.status());
    }
    await cleanup.dispose();
    await api?.dispose();
  });

  test("a regular user reads the golden sprint's commitments", async ({ page }) => {
    await openGroup(page, "Delivery");
    await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
    await pickGoldenSprint(page);

    await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: initial and final scope by sprint" })).toBeVisible();
    // The team level adds the sprint's capacity and the load it puts on it (this team has no calendar or default figure, so the figures are the computed members × working days).
    const sprintTable = page.getByRole("table", { name: "Sprints and their figures" });
    for (const name of ["Capacity (MD)", "Load"]) await expect(sprintTable.getByRole("columnheader", { name, exact: true })).toBeVisible();

    // Initial = the committed scope, final = the final scope (A17) — the generator's own figures.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(0)).toHaveText(GOLDEN.name);
    await expect(cells.nth(1)).toHaveText(teamName);
    await expect(cells.nth(3)).toHaveText(md(GOLDEN.committedMd));
    await expect(cells.nth(4)).toHaveText(String(GOLDEN.committedItems));
    await expect(cells.nth(5)).toHaveText(md(GOLDEN.finalMd));
    await expect(cells.nth(6)).toHaveText(String(GOLDEN.finalItems));
    // Nothing moved since the sprint closed, so no drift badge.
    await expect(page.getByText("Drift", { exact: true })).toHaveCount(0);
    await expectLegacyVelocityRedirect(page);
  });

  test("the user regroups throughput by month", async ({ page }) => {
    await openGoldenThroughput(page);
    // Switching tabs carries the period and team with it.
    await expect(page).toHaveURL(/\/reports\/throughput\?/);
    await expect(page).toHaveURL(new RegExp(`sprintId=${GOLDEN.sprintId}`));

    // The sprint view: what was done inside the golden sprint while in it.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(3)).toHaveText(md(GOLDEN.deliveredMd));
    await expect(cells.nth(4)).toHaveText(String(GOLDEN.deliveredItems));

    // The period view, by week (the default): a row per Monday across the sprint's window.
    await expect(page.getByText(/^Delivered in the period: [\d.]+ MD \(\d+ items?\)$/)).toBeVisible();
    const bucketTable = page.getByRole("table", { name: "Delivered per period, as a table" });
    await expect(bucketTable.getByRole("columnheader", { name: "Week starting" })).toBeVisible();
    const bucketRows = bucketTable.getByRole("row");
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
    expect(await bucketRows.count(), "header + at least two weekly buckets").toBeGreaterThan(2);

    // Regroup by month: the URL, the table's column and its rows (one month covers the window) follow.
    await openFilters(page);
    await page.getByRole("radiogroup", { name: "Group by" }).getByText("Month", { exact: true }).click();
    await expect(page).toHaveURL(/bucket=MONTH/);
    await expect(bucketTable.getByRole("columnheader", { name: "Month", exact: true })).toBeVisible();
    await expect(bucketRows).toHaveCount(2);
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}$/);
    await expect(page.getByRole("group", { name: "Chart: delivered scope by period" })).toBeVisible();
  });

  test("a user switches throughput to the cumulative view", ({ page }) => switchThroughputToCumulative(page));

  test("a user folds and unfolds the report filters and the choice is remembered", async ({ page }) => {
    await foldAndUnfoldFilters(page);
  });

  test("the user reads the golden sprint's scope movement", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickGoldenSprint(page);
    await page.getByRole("tab", { name: "Sprint consistency", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Sprint consistency", exact: true })).toBeVisible();

    await expect(page.getByRole("group", { name: "Chart: committed and delivered scope by sprint" })).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: final scope split into carried over, delivered and dropped, by sprint" })).toBeVisible();

    // The full table, in column order: committed, added, removed, final, delivered, carried over, dropped.
    const cells = goldenRow(page).getByRole("cell");
    await expect(cells.nth(3)).toHaveText(mdItems(GOLDEN.committedMd, GOLDEN.committedItems));
    await expect(cells.nth(4)).toHaveText(mdItems(GOLDEN.addedMd, GOLDEN.addedItems));
    await expect(cells.nth(5)).toHaveText(mdItems(GOLDEN.removedMd, GOLDEN.removedItems));
    await expect(cells.nth(6)).toHaveText(mdItems(GOLDEN.finalMd, GOLDEN.finalItems));
    await expect(cells.nth(7)).toHaveText(mdItems(GOLDEN.deliveredMd, GOLDEN.deliveredItems));
    await expect(cells.nth(8)).toHaveText(mdItems(GOLDEN.carriedOverMd, GOLDEN.carriedOverItems));
    await expect(cells.nth(9)).toHaveText(mdItems(GOLDEN.droppedMd, GOLDEN.droppedItems));

    await sliceSprintConsistency(page);
  });

  test("the user reads cycle time", async ({ page }) => {
    await readCycleTime(page);
  });

  test("a user widens the cycle time histogram's long tail", ({ page }) => widenCycleTimeTail(page));

  test("the user reads task estimation accuracy", ({ page }) => readTaskAccuracy(page));

  test("the user reads the last three sprints and drills to a member", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickTeamAndPeriod(page, "Last 3 sprints");
    await expect(page).toHaveURL(/lastSprints=3/);
    await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));

    // The team's three newest sprints, one row each.
    const sprintRows = page.getByRole("row", { name: /FLO Sprint \d+/ });
    await expect(sprintRows).toHaveCount(3);

    // The team level lists its members; take the first and remember its figures.
    await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();
    const memberLink = page.getByRole("link", { name: /^Show Sample User \d+$/ }).first();
    const memberName = (await memberLink.innerText()).trim();
    const memberRow = page.getByRole("row", { name: new RegExp(`^Show ${memberName} `) });
    const memberInitialMd = Number(await memberRow.getByRole("cell").nth(1).innerText());
    expect(memberInitialMd).toBeGreaterThanOrEqual(0);

    // Drilling narrows to the person: the URL names them, the member table is gone (nothing is
    // below a person), and their sprint rows add up to the figure the team level showed for them.
    await memberLink.click();
    await expect(page).toHaveURL(/accountId=/);
    await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toHaveCount(0);
    await expect(sprintRows.first()).toBeVisible();
    const perSprint = await sprintRows.evaluateAll((rows) =>
      rows.map((row) => Number(row.querySelectorAll("td")[3]?.textContent ?? Number.NaN)),
    );
    expect(perSprint.length).toBeGreaterThan(0);
    expect(perSprint.reduce((sum, value) => sum + value, 0)).toBeCloseTo(memberInitialMd, 1);
  });

  test("the user counts work in progress by stage and by status", async ({ page }) => {
    await openReport(page, "/reports/wip", `&teamId=${teamId}`);
    await expect(page.getByRole("heading", { level: 2, name: "WIP", exact: true })).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();

    // By stage (the default): the five stages (WAITING included, A30), as the chart's text alternative.
    const bands = page.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    for (const stage of ["Not started", "In progress", "Waiting", "Done", "Unmapped status"]) {
      await expect(bands.getByRole("cell", { name: stage, exact: true })).toBeVisible();
    }

    // By status: the URL, the caption and the bands all follow — the workflow's own statuses.
    await openFilters(page);
    await page.getByRole("radiogroup", { name: "Count by" }).getByText("Status", { exact: true }).click();
    await expect(page).toHaveURL(/by=STATUS/);
    await expect(page.getByText("By status, ordered by stage and then by name.")).toBeVisible();
    for (const status of ["To Do", "In Progress", "Done"]) {
      await expect(bands.getByRole("cell", { name: status, exact: true })).toBeVisible();
    }
    await expect(bands.getByRole("cell", { name: "Not started", exact: true })).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Chart: items at the end of each day, stacked by band" })).toBeVisible();
  });

  test("the user reads aging work in progress and blocked time", async ({ page }) => {
    await openReport(page, "/reports/wip", `&teamId=${teamId}`);
    await page.getByRole("tab", { name: "Aging WIP", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Aging WIP", exact: true })).toBeVisible();
    // The tab hop keeps the team.
    await expect(page).toHaveURL(new RegExp(`/reports/aging-wip\\?.*teamId=${teamId}`));

    // The task thresholds (the team finished plenty), and the open work, oldest first.
    const thresholds = page.getByRole("group", { name: "Tasks", exact: true });
    for (const percentile of ["p50", "p85", "p95"]) await expect(thresholds.getByText(percentile, { exact: true })).toBeVisible();
    const openWork = page.getByRole("table", { name: "Open work, oldest first" });
    await expect(openWork.getByRole("columnheader", { name: "Age (working days)" })).toBeVisible();
    expect(await openWork.getByRole("row").count(), "header + at least one open item").toBeGreaterThan(1);
    await expect(openWork.getByRole("row").nth(1)).toContainText(/\b(FLO|PLT|GTM|OPS)-\d+/);

    // Blocked time: both distributions, the "blocked at all" line and the most-blocked table.
    await page.getByRole("tab", { name: "Blocked time", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Blocked time", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked working days", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Blocked share of cycle", exact: true })).toBeVisible();
    await expect(page.getByText(/^\d+ of \d+ finished items? (was|were) blocked at all/)).toBeVisible();
    await expect(percentilesOrNotice(page).first()).toBeVisible();
    await expect(page.getByRole("heading", { name: "Most blocked items", exact: true })).toBeVisible();
  });

  test("the user drills from a domain into the golden epic's progress", async ({ page }) => {
    await drillIntoGoldenEpic(page);
  });

  test("the user reads the data-quality findings", async ({ page }) => {
    await readDataQualityFindings(page);
  });

  test("the user reads the cost matrix and drills to a team", async ({ page }) => {
    await readCostMatrix(page);
  });

  test("the user sees the four overview tiles on Home and follows one into its report", async ({ page }) => {
    await login(page, reader.email, reader.password);
    await expect(page.getByRole("heading", { level: 2, name: "Flow", exact: true })).toBeVisible();

    // The four tiles of the whole unit, each with its title as the way into the report.
    const overview = page.getByRole("group", { name: "Unit overview" });
    for (const title of ["Commitments and velocity", "Cycle time", "Work in progress", "Data quality"]) {
      const tile = overview.getByRole("region", { name: title, exact: true });
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute("aria-busy", "false");
      await expect(tile.getByRole("link", { name: title, exact: true })).toBeVisible();
    }
    // The commitments tile lists this spec's team with its last closed sprint.
    await expect(
      overview.getByRole("table", { name: "Each team's last closed sprint, as a table" }).getByRole("row", { name: new RegExp(`^${teamName} FLO Sprint \\d+`) }),
    ).toBeVisible();

    // A tile link never lands bare: the report opens with the tile's own period in the URL.
    await overview.getByRole("link", { name: "Cycle time", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/cycle-time\?.*from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/);
    await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

    await page.getByRole("link", { name: "Home", exact: true }).click();
    await overview.getByRole("link", { name: "Commitments and velocity", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/commitments\?.*lastSprints=1/);
    await expect(page.getByRole("heading", { level: 2, name: "Commitments", exact: true })).toBeVisible();
  });

  test("the user reads epic estimation accuracy against each epic's own estimate", async ({ page }) => {
    await readEpicAccuracy(page);
  });

  test("the user reads how estimates were adjusted", async ({ page }) => {
    await readEstimateAdjustments(page);
  });

  test("the user reads reported time beside flow efficiency", async ({ page }) => {
    await readReportedTime(page);
  });

  test("the user reads the estimated backlog and what it means in sprints", async ({ page }) => {
    await readEstimatedBacklog(page);
  });

  test("the user dives into the golden sprint and drills an epic to its tasks", async ({ page }) => {
    await diveIntoGoldenSprint(page);
  });

  test("the populated flow, epic and cost pages have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    expect(
      await populatedViolations(page, [
        { path: "/reports/wip", extra: `&teamId=${teamId}`, settled: (p) => p.getByRole("table", { name: "Latest day, average and peak per band, as a table" }) },
        { path: "/reports/epic-progress", extra: `&epicId=${GOLDEN_EPIC.issueKey}`, settled: (p) => p.getByRole("heading", { level: 3, name: "Plan", exact: true }) },
        { path: "/reports/cost-matrix", settled: (p) => p.getByRole("table", { name: /^Man-days logged in the period/ }) },
      ]),
    ).toEqual({});
  });

  // Home is scanned once its four tiles loaded; its commitments tile's table scrolls sideways in the
  // half-width tile (a focusable, named scroll region), and the drift table's "reconstructed" badge
  // is the orange light badge — both were findings of this very scan.
  test("the populated Home overview and data-quality page have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    // Home first: the session lands there. Its four tiles must have loaded before the scan.
    for (const title of ["Commitments and velocity", "Cycle time", "Work in progress", "Data quality"]) {
      await expect(page.getByRole("region", { name: title, exact: true })).toHaveAttribute("aria-busy", "false");
    }
    const home = await axeViolations(page);
    const rest = await populatedViolations(page, [{ path: "/reports/data-quality", settled: (p) => p.getByRole("group", { name: "Findings" }) }]);
    expect({ ...(home.length > 0 ? { "/": home } : {}), ...rest }).toEqual({});
  });
});
