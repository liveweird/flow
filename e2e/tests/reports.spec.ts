// The v0.3.0 REPORTS journey, read the way D12 says everyone reads them: a NON-ADMIN user opens
// the Delivery and Estimation reports over a synced-and-derived Jira-stub connection and finds the
// golden FLO sprint's figures (`sample-data/jira/expected.json` `golden.sprint`) exactly; batch 2
// adds the Flow metrics group (WIP, aging, blocked time, epic progress), Data quality, the Cost
// matrix, Home's unit overview and Velocity's last-N-sprints and member drill; the checkup's A15
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

/** The epic-accuracy journey: empty default window, both views, the golden epic's ratio, then domain → team → member. */
async function readEpicAccuracy(page: Page): Promise<void> {
  // The default window holds no finished epic: the empty state, not a block of zeros.
  await openReport(page, "/reports/epic-estimation-accuracy");
  await expect(page.getByRole("heading", { level: 2, name: "Epic estimation accuracy", exact: true })).toBeVisible();
  await expect(page.getByText("No data in this period")).toBeVisible();
  const epics = page.getByRole("table", { name: "Finished epics", exact: true });
  await expect(epics).toHaveCount(0);

  // The year-long window holds the stub's finished epics: both views, each a distribution with its accounting.
  await page.goto(`/reports/epic-estimation-accuracy?connectionId=${dataSourceId}&${EPIC_WINDOW}`);
  await expect(page.getByRole("heading", { name: "Against the own estimate at start", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Against the own estimate at done", exact: true })).toBeVisible();
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();

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

  // The domain narrows the epics: GTM's only, the golden FLO epic gone; clearing it brings it back.
  await pickFilter(page, "Domain", "GTM");
  await expect(page).toHaveURL(/domain=GTM/);
  await expect(epics.getByRole("row", { name: /^GTM-\d+/ }).first()).toBeVisible();
  await expect(epics.getByRole("row", { name: /^(FLO|OPS|PLT)-\d+/ })).toHaveCount(0);
  await clearFilter(page, "Domain");
  await expect(golden).toBeVisible();

  // The team level: the By team row is the way in; the team's few epics fall below the minimum sample.
  await page.getByRole("table", { name: "By team", exact: true }).getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(golden).toBeVisible();
  await expect(epics.getByRole("row", { name: /^(GTM|OPS|PLT)-\d+/ })).toHaveCount(0);
  await expect(page.getByRole("note").filter({ hasText: /^Only \d+ items in this selection/ })).toHaveCount(2);
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(0);

  // A single person has no epics of their own: one note, no table.
  await pickFilter(page, "Member", memberName);
  await expect(page).toHaveURL(/accountId=/);
  await expect(page.getByRole("note").filter({ hasText: "Epics aren't attributed to individual people" })).toBeVisible();
  await expect(epics).toHaveCount(0);
}

/** The estimate-adjustments journey: tasks and epics blocks, the activity-type filter, then team → member. */
async function readEstimateAdjustments(page: Page): Promise<void> {
  await openReport(page, "/reports/estimate-adjustments", "", EPIC_WINDOW);
  await expect(page.getByRole("heading", { level: 2, name: "Estimate adjustments", exact: true })).toBeVisible();
  await expect(page.getByText("The domain view applies to tasks; epics always read their own space.")).toBeVisible();

  // Tasks and epics each read started / changed after start / estimated late / share changed.
  const tasks = page.getByRole("group", { name: "Tasks", exact: true });
  const epics = page.getByRole("group", { name: "Epics", exact: true });
  for (const block of [tasks, epics]) {
    const started = Number(await blockFigure(block, "Started"));
    const changed = Number(await blockFigure(block, "Changed after start"));
    const late = Number(await blockFigure(block, "Estimated late"));
    // The share is withheld below the minimum sample (5, as the server's `meta.minSampleSize`): the stub starts far more.
    expect(started, "started count clears the minimum sample, so the share is shown").toBeGreaterThanOrEqual(5);
    expect(changed).toBeLessThanOrEqual(started);
    expect(late, "estimated late is counted within changed after start").toBeLessThanOrEqual(changed);
    expect(await blockFigure(block, "Share changed")).toMatch(/^\d+(\.\d+)?%$/);
  }
  // Each kind's change distribution (start → done) and its accounting.
  await expect(page.getByRole("heading", { name: "Change from start to done", exact: true })).toHaveCount(2);
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);

  // The activity type narrows the TASKS (epics carry none): fewer tasks started, the filter in the URL.
  const allTasks = Number(await blockFigure(tasks, "Started"));
  await pickFilter(page, "Activity type", "Bug");
  await expect(page).toHaveURL(/activityType=Bug/);
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBeLessThan(allTasks);
  await clearFilter(page, "Activity type");
  await expect.poll(async () => Number(await blockFigure(tasks, "Started"))).toBe(allTasks);

  // Down the org drill: the team row, then its members; a single person has no epic figures.
  await page.getByRole("table", { name: "By team", exact: true }).getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();
  await pickFilter(page, "Member", memberName);
  await expect(page).toHaveURL(/accountId=/);
  await expect(page.getByRole("note").filter({ hasText: "Epics aren't attributed to individual people" })).toBeVisible();
  await expect(epics).toHaveCount(0);
  await expect(tasks).toBeVisible();
}

/** The reported-time journey: the Estimation tab hop, both distributions, the domain and domain-view controls, the team drill. */
async function readReportedTime(page: Page): Promise<void> {
  // Reached through the Estimation tabs: the route is `reported-time-ratio`, the tab "Reported time".
  await openReport(page, "/reports/epic-estimation-accuracy");
  await page.getByRole("tab", { name: "Reported time", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/reports/reported-time-ratio\\?.*connectionId=${dataSourceId}`));
  await expect(page.getByRole("heading", { level: 2, name: "Reported time", exact: true })).toBeVisible();

  // Two measures, two distributions, each with its OWN accounting (the partitions differ).
  await expect(page.getByRole("heading", { name: "Reported time ÷ cycle time", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();
  await expect(page.getByText("Median (p50)", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("heading", { name: "Left out of this distribution" })).toHaveCount(2);
  await expectAccountingToReconcile(page, 2);
  await expect(page.getByText(/^Very short cycles give very large ratios/)).toBeVisible();
  await expect(page.getByRole("table", { name: /^Histogram, as a table — Reported time ÷ cycle time$/ })).toBeVisible();

  // The domain narrows what finished: fewer items in the population, the filter in the URL.
  const all = await finishedInPeriod(page);
  await pickFilter(page, "Domain", "FLO");
  await expect(page).toHaveURL(/domain=FLO/);
  await expect.poll(() => finishedInPeriod(page)).toBeLessThan(all);
  await clearFilter(page, "Domain");
  await expect.poll(() => finishedInPeriod(page)).toBe(all);

  // The domain view (delivered in / earned in) is a report-specific control that travels in the URL.
  await page.getByRole("radiogroup", { name: "Domain view" }).getByText("Earned in", { exact: true }).click();
  await expect(page).toHaveURL(/domainView=EPIC/);
  await expect(page.getByRole("heading", { name: "Flow efficiency", exact: true })).toBeVisible();

  // The team row is the way in; its members follow.
  await page.getByRole("table", { name: "By team", exact: true }).getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
  await expect(page.getByRole("heading", { level: 3, name: "By member", exact: true })).toBeVisible();
  expect(await finishedInPeriod(page), "the team's finished work is part of the unit's").toBeLessThan(all);
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

  test("a regular user reads the golden sprint's velocity", async ({ page }) => {
    await openGroup(page, "Delivery");
    await expect(page.getByRole("heading", { level: 2, name: "Velocity", exact: true })).toBeVisible();
    await pickGoldenSprint(page);

    await expect(page.getByText(/^Derived .* · configuration revision \d+$/)).toBeVisible();
    await expect(page.getByRole("group", { name: "Chart: initial and final scope by sprint" })).toBeVisible();

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
  });

  test("the user regroups throughput by month", async ({ page }) => {
    await openGroup(page, "Delivery");
    await pickGoldenSprint(page);
    await page.getByRole("tab", { name: "Throughput", exact: true }).click();
    await expect(page.getByRole("heading", { level: 2, name: "Throughput", exact: true })).toBeVisible();
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
    await page.getByRole("radiogroup", { name: "Group by" }).getByText("Month", { exact: true }).click();
    await expect(page).toHaveURL(/bucket=MONTH/);
    await expect(bucketTable.getByRole("columnheader", { name: "Month", exact: true })).toBeVisible();
    await expect(bucketRows).toHaveCount(2);
    await expect(bucketRows.nth(1).getByRole("cell").first()).toHaveText(/^\d{4}-\d{2}$/);
    await expect(page.getByRole("group", { name: "Chart: delivered scope by period" })).toBeVisible();
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
  });

  test("the user reads cycle time", async ({ page }) => {
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

    // The epics beside the tasks: the stub's epics all finished before the sprint-narrowed window, so a year-long
    // window reads them — two views of their own, each with its own accounting, next to the tasks' two.
    await page.goto(`/reports/cycle-time?connectionId=${dataSourceId}&${EPIC_WINDOW}`);
    await expect(page.getByRole("heading", { level: 3, name: "Epics", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Epics: elapsed days", exact: true })).toBeVisible();
    await expectAccountingToReconcile(page, 4);
    // Owner team, never a person: the table's team row is the way in, and the narrowed read keeps the block without the table.
    const owners = page.getByRole("table", { name: "Epics by owner team", exact: true });
    await owners.getByRole("link", { name: `Show ${teamName}`, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`teamId=${teamId}`));
    await expect(page.getByRole("heading", { name: "Epics: working days", exact: true })).toBeVisible();
    await expect(owners).toHaveCount(0);
  });

  test("the user reads task estimation accuracy", async ({ page }) => {
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
  });

  test("the user reads the last three sprints and drills to a member", async ({ page }) => {
    await openGroup(page, "Delivery");
    const team = page.getByRole("combobox", { name: "Team", exact: true });
    await team.click();
    await team.fill(teamName);
    await page.getByRole("option", { name: teamName, exact: true }).click();
    await page.getByRole("combobox", { name: "Period", exact: true }).click();
    await page.getByRole("option", { name: "Last 3 sprints", exact: true }).click();
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

    // By stage (the default): the four stages, as the chart's text alternative.
    const bands = page.getByRole("table", { name: "Latest day, average and peak per band, as a table" });
    for (const stage of ["Not started", "In progress", "Done", "Unmapped status"]) {
      await expect(bands.getByRole("cell", { name: stage, exact: true })).toBeVisible();
    }

    // By status: the URL, the caption and the bands all follow — the workflow's own statuses.
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
    for (const title of ["Velocity and throughput", "Cycle time", "Work in progress", "Data quality"]) {
      const tile = overview.getByRole("region", { name: title, exact: true });
      await expect(tile).toBeVisible();
      await expect(tile).toHaveAttribute("aria-busy", "false");
      await expect(tile.getByRole("link", { name: title, exact: true })).toBeVisible();
    }
    // The velocity tile lists this spec's team with its last closed sprint.
    await expect(
      overview.getByRole("table", { name: "Each team's last closed sprint, as a table" }).getByRole("row", { name: new RegExp(`^${teamName} FLO Sprint \\d+`) }),
    ).toBeVisible();

    // A tile link never lands bare: the report opens with the tile's own period in the URL.
    await overview.getByRole("link", { name: "Cycle time", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/cycle-time\?.*from=\d{4}-\d{2}-\d{2}&to=\d{4}-\d{2}-\d{2}/);
    await expect(page.getByRole("heading", { level: 2, name: "Cycle time", exact: true })).toBeVisible();

    await page.getByRole("link", { name: "Home", exact: true }).click();
    await overview.getByRole("link", { name: "Velocity and throughput", exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/velocity\?.*lastSprints=1/);
    await expect(page.getByRole("heading", { level: 2, name: "Velocity", exact: true })).toBeVisible();
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

  // Home is scanned once its four tiles loaded; its velocity tile's table scrolls sideways in the
  // half-width tile (a focusable, named scroll region), and the drift table's "reconstructed" badge
  // is the orange light badge — both were findings of this very scan.
  test("the populated Home overview and data-quality page have no WCAG A/AA violations", async ({ page }) => {
    await login(page, reader.email, reader.password);
    // Home first: the session lands there. Its four tiles must have loaded before the scan.
    for (const title of ["Velocity and throughput", "Cycle time", "Work in progress", "Data quality"]) {
      await expect(page.getByRole("region", { name: title, exact: true })).toHaveAttribute("aria-busy", "false");
    }
    const home = await axeViolations(page);
    const rest = await populatedViolations(page, [{ path: "/reports/data-quality", settled: (p) => p.getByRole("group", { name: "Findings" }) }]);
    expect({ ...(home.length > 0 ? { "/": home } : {}), ...rest }).toEqual({});
  });
});
